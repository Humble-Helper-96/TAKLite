package com.taklite.client.mod.imports;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Imports kept on the phone:
 * <pre>
 *   base/index.json          list of imports (name, origin, visibility, bounds)
 *   base/&lt;id&gt;/features.json  the converted map items
 *   base/&lt;id&gt;/photo1.jpg     attachments
 * </pre>
 */
final class ImportStore {

    static final class Entry {
        String id;
        String name;
        /** e.g. "Data package from ALPHA-1" or "File". */
        String origin;
        String key;
        long importedAt;
        boolean visible = true;
        int count;
        double north;
        double east;
        double south;
        double west;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("origin", origin)
                    .put("key", key).put("importedAt", importedAt).put("visible", visible)
                    .put("count", count).put("bbox", new JSONArray()
                            .put(north).put(east).put(south).put(west));
        }

        Entry copy() {
            Entry e = new Entry();
            e.id = id;
            e.name = name;
            e.origin = origin;
            e.key = key;
            e.importedAt = importedAt;
            e.visible = visible;
            e.count = count;
            e.north = north;
            e.east = east;
            e.south = south;
            e.west = west;
            return e;
        }

        static Entry fromJson(JSONObject o) throws JSONException {
            Entry e = new Entry();
            e.id = o.getString("id");
            e.name = o.optString("name", "");
            e.origin = o.optString("origin", "");
            e.key = o.optString("key", "");
            e.importedAt = o.optLong("importedAt");
            e.visible = o.optBoolean("visible", true);
            e.count = o.optInt("count");
            JSONArray b = o.optJSONArray("bbox");
            if (b != null && b.length() == 4) {
                e.north = b.getDouble(0);
                e.east = b.getDouble(1);
                e.south = b.getDouble(2);
                e.west = b.getDouble(3);
            }
            return e;
        }
    }

    private final File base;
    /** index.json as last read or written; callers get copies, so they can't change it. */
    private List<Entry> index;

    ImportStore(File base) {
        this.base = base;
    }

    /** A fresh, empty folder to import into; pass it to {@link #add}. */
    synchronized File newStagingDir() throws IOException {
        File dir = new File(base, "staging-" + UUID.randomUUID());
        if (!dir.mkdirs()) throw new IOException("Could not create " + dir);
        return dir;
    }

    /** Saves an import, replacing any earlier one with the same key (e.g. a re-sent package). */
    synchronized Entry add(Importer.Result result, String origin, File stagingDir) throws IOException {
        List<Entry> entries = list();
        for (Entry old : new ArrayList<>(entries)) {
            if (old.key.equals(result.key)) {
                entries.remove(old);
                deleteTree(dir(old.id));
            }
        }
        Entry e = new Entry();
        e.id = UUID.randomUUID().toString();
        e.name = result.name;
        e.origin = origin;
        e.key = result.key;
        e.importedAt = System.currentTimeMillis();
        e.count = result.features.size();
        e.north = -90;
        e.south = 90;
        e.east = -180;
        e.west = 180;
        JSONArray features = new JSONArray();
        try {
            for (Feature f : result.features) {
                features.put(f.toJson());
                for (int i = 0; i < f.pointCount(); i++) {
                    e.north = Math.max(e.north, f.lat(i));
                    e.south = Math.min(e.south, f.lat(i));
                    e.east = Math.max(e.east, f.lon(i));
                    e.west = Math.min(e.west, f.lon(i));
                }
            }
        } catch (JSONException ex) {
            throw new IOException(ex);
        }
        writeText(new File(stagingDir, "features.json"), features.toString());
        if (!stagingDir.renameTo(dir(e.id))) throw new IOException("Could not store import");
        entries.add(0, e);
        saveIndex(entries);
        return e;
    }

    synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : index()) out.add(e.copy());
        return out;
    }

    synchronized Entry get(String id) {
        for (Entry e : index()) {
            if (e.id.equals(id)) return e.copy();
        }
        return null;
    }

    private List<Entry> index() {
        if (index != null) return index;
        index = new ArrayList<>();
        File file = new File(base, "index.json");
        if (!file.exists()) return index;
        try {
            JSONArray a = new JSONArray(readText(file));
            for (int i = 0; i < a.length(); i++) index.add(Entry.fromJson(a.getJSONObject(i)));
        } catch (IOException | JSONException e) {
            // A damaged index loses the list, not the app.
        }
        return index;
    }

    synchronized List<Feature> features(String id) throws IOException {
        List<Feature> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(readText(new File(dir(id), "features.json")));
            for (int i = 0; i < a.length(); i++) out.add(Feature.fromJson(a.getJSONObject(i)));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return out;
    }

    synchronized void setVisible(String id, boolean visible) throws IOException {
        List<Entry> entries = list();
        for (Entry e : entries) {
            if (e.id.equals(id)) e.visible = visible;
        }
        saveIndex(entries);
    }

    synchronized void delete(String id) throws IOException {
        List<Entry> entries = list();
        for (Entry e : new ArrayList<>(entries)) {
            if (e.id.equals(id)) entries.remove(e);
        }
        saveIndex(entries);
        deleteTree(dir(id));
    }

    File dir(String id) {
        return new File(base, id);
    }

    /**
     * Removes staging folders left behind by an import that was interrupted. Runs at app
     * start, while a new import may already be filling its own staging folder, so only
     * folders untouched for an hour are removed.
     */
    synchronized void cleanStaging() {
        File[] files = base.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 60 * 60 * 1000L;
        for (File f : files) {
            if (f.getName().startsWith("staging-") && f.lastModified() < cutoff) deleteTree(f);
        }
    }

    private void saveIndex(List<Entry> entries) throws IOException {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : entries) a.put(e.toJson());
        } catch (JSONException ex) {
            throw new IOException(ex);
        }
        File tmp = new File(base, "index.json.tmp");
        writeText(tmp, a.toString());
        if (!tmp.renameTo(new File(base, "index.json"))) throw new IOException("Could not save import list");
        index = new ArrayList<>();
        for (Entry e : entries) index.add(e.copy());
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteTree(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            return new String(data, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
