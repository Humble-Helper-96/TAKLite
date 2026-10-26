package com.taklite.client.mod.imports;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.taklite.client.SettingsActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Receives data packages that other TAK users send to this device.
 *
 * ATAK/iTAK/WinTAK share a package by uploading it to the TAK server and sending
 * a CoT "file transfer request" (type b-f-t-r) whose {@code <fileshare>} gives
 * the download URL (usually https://server:8443/Marti/sync/content?hash=...),
 * size and SHA-256. This downloads it with the device's TAK client certificate,
 * checks the hash and imports it.
 */
final class DataPackages {

    private static final String TAG = "DataPackages";
    private static final long MAX_PACKAGE_BYTES = 200L * 1024 * 1024;
    private static final String PREFS = "TAKLitePrefs";
    /**
     * "hash\timport id" lines for packages already received, so a re-send is skipped
     * while that import still exists (deleting it lets the package come in again).
     */
    private static final String KEY_RECEIVED = "mod_received_packages";
    private static final int REMEMBER_RECEIVED = 200;
    private static final int TIMEOUT_MS = 20000;

    private static final Set<String> inFlight = new HashSet<>();

    private DataPackages() {
    }

    static void onFileShare(final Context context, String cot) {
        final XmlNode event;
        try {
            event = XmlNode.parse(new java.io.ByteArrayInputStream(cot.getBytes("UTF-8")));
        } catch (IOException e) {
            return;
        }
        if (!"event".equals(event.name) || !"b-f-t-r".equals(event.attr("type"))) return;
        XmlNode detail = event.child("detail");
        final XmlNode share = detail == null ? null : detail.child("fileshare");
        if (share == null || share.attr("senderUrl") == null) return;

        final String url = share.attr("senderUrl");
        final String sha256 = lower(share.attr("sha256"));
        final String filename = share.attr("filename") != null ? share.attr("filename") : "package.zip";
        final String name = share.attr("name") != null && !share.attr("name").isEmpty()
                ? share.attr("name") : Importer.stripExtension(filename);
        final String sender = share.attr("senderCallsign") != null ? share.attr("senderCallsign") : "a TAK user";
        long size = parseLong(share.attr("sizeInBytes"));

        final String dedupeKey = sha256 != null ? sha256 : url;
        synchronized (inFlight) {
            if (inFlight.contains(dedupeKey) || alreadyReceived(context, dedupeKey)) {
                Log.d(TAG, "Ignoring repeat of " + name);
                return;
            }
            inFlight.add(dedupeKey);
        }
        if (size > MAX_PACKAGE_BYTES) {
            Imports.announce("Data package not imported",
                    "\"" + name + "\" from " + sender + " is too large (" + (size >> 20) + " MB).");
            done(dedupeKey);
            return;
        }

        new Thread(() -> {
            File tmp = new File(context.getCacheDir(), "package-" + System.nanoTime() + ".zip");
            try {
                download(context, url, tmp);
                if (sha256 != null && !sha256.equals(Importer.sha256(tmp))) {
                    throw new IOException("download is corrupt (SHA-256 mismatch)");
                }
                ImportStore.Entry entry = Imports.importFile(tmp, filename, "Data package from " + sender);
                remember(context, dedupeKey, entry.id);
                Imports.announce("Data package received",
                        "\"" + entry.name + "\" from " + sender + ": " + entry.count + " items added to the map.");
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Data package " + name + " failed", e);
                Imports.announce("Data package failed",
                        "\"" + name + "\" from " + sender + ": " + e.getMessage());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                done(dedupeKey);
            }
        }, "DataPackageDownload").start();
    }

    /**
     * Downloads with the TAK client certificate. If the URL names a host the phone
     * can't reach (servers often advertise an internal name), retries against the
     * server address configured in TAK Lite.
     */
    private static void download(Context context, String url, File dest) throws IOException {
        try {
            downloadOnce(context, url, dest);
        } catch (IOException first) {
            String server = SettingsActivity.getTakServerAddress(context);
            URL u = new URL(url);
            if (server == null || server.isEmpty() || server.equalsIgnoreCase(u.getHost())) throw first;
            String retry = new URL(u.getProtocol(), server, u.getPort(), u.getFile()).toString();
            Log.w(TAG, "Download from " + u.getHost() + " failed (" + first.getMessage() + "), trying " + server);
            downloadOnce(context, retry, dest);
        }
    }

    private static void downloadOnce(Context context, String url, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(mutualTls(context).getSocketFactory());
                // Same as TAK Lite's own server API calls: the server certificate must chain
                // to the TAK trust store, but the name it was issued for isn't checked.
                https.setHostnameVerifier((host, session) -> true);
            }
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("server answered HTTP " + code);
            long length = conn.getContentLengthLong();
            if (length > MAX_PACKAGE_BYTES) throw new IOException("package is too large");
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_PACKAGE_BYTES) throw new IOException("package is too large");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static SSLContext mutualTls(Context context) throws IOException {
        String trustPath = SettingsActivity.getTakTrustStorePath(context);
        String certPath = SettingsActivity.getTakClientCertPath(context);
        String password = SettingsActivity.getTakCertPassword(context);
        if (trustPath == null || trustPath.isEmpty() || certPath == null || certPath.isEmpty()) {
            throw new IOException("TAK Lite is not enrolled with a TAK server");
        }
        char[] pw = (password == null ? "" : password).toCharArray();
        try {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(trustPath)) {
                trust.load(in, pw);
            }
            KeyStore client = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(certPath)) {
                client.load(in, pw);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(client, pw);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ssl;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not load TAK certificates: " + e.getMessage(), e);
        }
    }

    private static boolean alreadyReceived(Context context, String key) {
        for (String line : prefs(context).getString(KEY_RECEIVED, "").split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length == 2 && parts[0].equals(key)) return Imports.store().get(parts[1]) != null;
        }
        return false;
    }

    private static synchronized void remember(Context context, String key, String importId) {
        List<String> lines = new ArrayList<>(Arrays.asList(prefs(context).getString(KEY_RECEIVED, "").split("\n")));
        lines.removeIf(line -> line.isEmpty() || line.startsWith(key + "\t"));
        lines.add(key + "\t" + importId);
        while (lines.size() > REMEMBER_RECEIVED) lines.remove(0);
        prefs(context).edit().putString(KEY_RECEIVED, String.join("\n", lines)).apply();
    }

    private static void done(String key) {
        synchronized (inFlight) {
            inFlight.remove(key);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String lower(String s) {
        return s == null || s.isEmpty() ? null : s.toLowerCase(Locale.US);
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
