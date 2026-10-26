package com.taklite.client.mod.imports;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Reads an imported file into {@link Feature}s. Handles:
 * <ul>
 *   <li>TAK data packages (mission packages): a zip with MANIFEST/manifest.xml
 *       listing .cot, .kml, .kmz, .gpx and attachments such as photos;</li>
 *   <li>KMZ and other zips without a manifest (every supported file inside);</li>
 *   <li>single .kml, .gpx and .cot files.</li>
 * </ul>
 * Photos attached to a CoT item are copied into {@code attachmentDir} and linked
 * to that item's marker.
 */
final class Importer {

    private static final long MAX_ENTRY_BYTES = 100L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    static final class Result {
        String name;
        /** Identifies "the same import" so a re-sent package replaces the old copy. */
        String key;
        final List<Feature> features = new ArrayList<>();
        int skipped;
    }

    private long totalRead;

    private Importer() {
    }

    static Result read(File file, String displayName, File attachmentDir) throws IOException {
        return new Importer().readFile(file, displayName, attachmentDir);
    }

    private Result readFile(File file, String displayName, File attachmentDir) throws IOException {
        Result result = new Result();
        result.name = stripExtension(displayName);
        if (isZip(file)) {
            try (ZipFile zip = new ZipFile(file)) {
                readZip(zip, result, attachmentDir);
            }
            if (result.key == null) result.key = "sha256:" + sha256(file);
        } else {
            result.key = "sha256:" + sha256(file);
            try (InputStream in = new FileInputStream(file)) {
                readDocument(in, displayName, result);
            }
        }
        if (result.features.isEmpty()) {
            throw new IOException("No map items found in " + displayName
                    + (result.skipped > 0 ? " (" + result.skipped + " unsupported files)" : ""));
        }
        return result;
    }

    private void readZip(ZipFile zip, Result result, File attachmentDir) throws IOException {
        if (zip.size() > MAX_ENTRIES) throw new IOException("Zip has too many files");
        ZipEntry manifest = null;
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            if (entry.getName().equalsIgnoreCase("MANIFEST/manifest.xml")) manifest = entry;
        }
        if (manifest == null) {
            // KMZ or a plain zip: take every file we understand.
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                if (isGeoFile(entry.getName())) {
                    try (InputStream in = open(zip, entry)) {
                        readDocument(in, entry.getName(), result);
                    }
                } else {
                    result.skipped++;
                }
            }
            return;
        }

        XmlNode root;
        try (InputStream in = open(zip, manifest)) {
            root = XmlNode.parse(in);
        }
        XmlNode config = root.child("Configuration");
        if (config != null) {
            String name = param(config, "name");
            String uid = param(config, "uid");
            if (name != null && !name.isEmpty()) result.name = name;
            if (uid != null && !uid.isEmpty()) result.key = "package:" + uid;
        }

        Map<String, Feature> byUid = new HashMap<>();
        List<String[]> attachments = new ArrayList<>(); // {zipEntry, owner uid}
        XmlNode contents = root.child("Contents");
        List<XmlNode> items = contents == null ? new ArrayList<XmlNode>() : contents.children("Content");
        for (XmlNode content : items) {
            if ("true".equalsIgnoreCase(content.attr("ignore"))) continue;
            String entryName = content.attr("zipEntry");
            ZipEntry entry = entryName == null ? null : zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                result.skipped++;
                continue;
            }
            String lower = entryName.toLowerCase(Locale.US);
            if (lower.endsWith(".cot") || lower.endsWith(".xml") || lower.endsWith(".kml")
                    || lower.endsWith(".kmz") || lower.endsWith(".gpx")) {
                int before = result.features.size();
                try (InputStream in = open(zip, entry)) {
                    readDocument(in, entryName, result);
                } catch (IOException e) {
                    result.skipped++;
                    continue;
                }
                for (int i = before; i < result.features.size(); i++) {
                    Feature f = result.features.get(i);
                    if (f.uid != null) byUid.put(f.uid, f);
                }
            } else if (isImage(lower) && param(content, "uid") != null) {
                attachments.add(new String[]{entryName, param(content, "uid")});
            } else {
                result.skipped++;
            }
        }

        int n = 0;
        for (String[] a : attachments) {
            Feature owner = byUid.get(a[1]);
            if (owner == null || owner.kind != Feature.POINT || owner.image != null) {
                result.skipped++;
                continue;
            }
            String ext = a[0].substring(a[0].lastIndexOf('.')).toLowerCase(Locale.US);
            String stored = "photo" + (++n) + ext;
            if (!attachmentDir.isDirectory() && !attachmentDir.mkdirs()) {
                throw new IOException("Could not create " + attachmentDir);
            }
            try (InputStream in = open(zip, zip.getEntry(a[0]));
                 OutputStream out = new FileOutputStream(new File(attachmentDir, stored))) {
                copy(in, out);
            }
            owner.image = stored;
        }
    }

    /** One KML, KMZ, GPX or CoT document. */
    private void readDocument(InputStream raw, String name, Result result) throws IOException {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".kmz")) {
            // A KMZ inside a data package: use its first .kml.
            try (ZipInputStream zin = new ZipInputStream(raw)) {
                for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
                    if (!e.isDirectory() && e.getName().toLowerCase(Locale.US).endsWith(".kml")) {
                        readDocument(new CountingStream(zin), e.getName(), result);
                        return;
                    }
                }
            }
            throw new IOException("No KML inside " + name);
        }
        XmlNode root = XmlNode.parse(raw);
        switch (root.name) {
            case "kml":
                result.features.addAll(KmlParser.parse(root));
                break;
            case "gpx":
                result.features.addAll(GpxParser.parse(root));
                break;
            case "event": {
                Feature f = CotShapes.toFeature(root);
                if (f != null) result.features.add(f);
                else result.skipped++;
                break;
            }
            default:
                result.skipped++;
        }
    }

    private InputStream open(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException(entry.getName() + " is too large");
        return new CountingStream(new BufferedInputStream(zip.getInputStream(entry)));
    }

    /** Stops zip bombs: caps what all entries together may decompress to. */
    private final class CountingStream extends FilterInputStream {
        private long read;

        CountingStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            totalRead += n;
            if (read > MAX_ENTRY_BYTES || totalRead > MAX_TOTAL_BYTES) {
                throw new IOException("Package expands to too much data");
            }
        }
    }

    private static String param(XmlNode node, String name) {
        for (XmlNode p : node.children("Parameter")) {
            if (name.equals(p.attr("name"))) return p.attr("value");
        }
        return null;
    }

    static boolean isGeoFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".kml") || lower.endsWith(".kmz") || lower.endsWith(".gpx") || lower.endsWith(".cot");
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private static boolean isZip(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[4];
            return in.read(head) == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        }
    }

    static String stripExtension(String name) {
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
