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
 * Data packages from the TAK server, downloaded with the device's TAK client
 * certificate, checked against their SHA-256 and imported.
 *
 * <ul>
 *   <li>Packages sent to this device: ATAK/iTAK/WinTAK upload the package to the
 *       server and send a CoT "file transfer request" (type b-f-t-r) whose
 *       {@code <fileshare>} gives the download URL (usually
 *       https://server:8443/Marti/sync/content?hash=...), size and SHA-256.</li>
 *   <li>Packages stored on the server: listed with the same query ATAK's
 *       "Data Package server query" uses, /Marti/sync/search?keywords=missionpackage.</li>
 * </ul>
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
    /** TAK Server's HTTPS API port (TAK Lite's own server calls use it too). */
    private static final int API_PORT = 8443;

    private static final Set<String> inFlight = new HashSet<>();

    private DataPackages() {
    }

    /** Called on a worker thread when a download+import ends: entry on success, else error. */
    interface Done {
        void finished(ImportStore.Entry entry, String error);
    }

    /** A package listed on the TAK server. */
    static final class ServerPackage {
        String name;
        String hash;
        String submitter;
        String submitted;
        long size;
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
        fetchAndImport(context, url, sha256, filename, name, "Data package from " + sender, size,
                (entry, error) -> {
                    if (entry != null) {
                        Imports.announce("Data package received",
                                "\"" + entry.name + "\" from " + sender + ": " + entry.count + " items added to the map.");
                    } else {
                        Imports.announce("Data package failed", "\"" + name + "\" from " + sender + ": " + error);
                    }
                }, dedupeKey);
    }

    /** Downloads a package listed on the server and imports it (replacing an earlier copy). */
    static void importFromServer(Context context, ServerPackage p, Done done) {
        String url = apiBase(context) + "/Marti/sync/content?hash=" + p.hash;
        String key = p.hash.toLowerCase(Locale.US);
        synchronized (inFlight) {
            if (inFlight.contains(key)) {
                done.finished(null, "already downloading");
                return;
            }
            inFlight.add(key);
        }
        String origin = "Server" + (p.submitter.isEmpty() ? "" : ", uploaded by " + p.submitter);
        // Server names usually already end in .zip ("Trails.zip"); don't make it "Trails.zip.zip".
        String filename = p.name.toLowerCase(Locale.US).endsWith(".zip") ? p.name : p.name + ".zip";
        fetchAndImport(context, url, key, filename, Importer.stripExtension(p.name), origin, p.size, done, key);
    }

    /**
     * Downloads, verifies and imports on a background thread. {@code key} must already be
     * in {@link #inFlight}; it is removed when finished.
     */
    private static void fetchAndImport(final Context context, final String url, final String sha256,
                                       final String filename, final String name, final String origin,
                                       long size, final Done done, final String key) {
        if (size > MAX_PACKAGE_BYTES) {
            done(key);
            done.finished(null, "too large (" + (size >> 20) + " MB, limit " + (MAX_PACKAGE_BYTES >> 20) + " MB)");
            return;
        }
        new Thread(() -> {
            File tmp = new File(context.getCacheDir(), "package-" + System.nanoTime() + ".zip");
            ImportStore.Entry entry = null;
            String error = null;
            try {
                download(context, url, tmp);
                Log.i(TAG, "Downloaded " + filename + ": " + tmp.length() + " bytes");
                if (sha256 != null && !sha256.equals(Importer.sha256(tmp))) {
                    throw new IOException("download is corrupt (SHA-256 mismatch)");
                }
                entry = Imports.importFile(tmp, filename, origin);
                Log.i(TAG, "Imported " + filename + " as \"" + entry.name + "\": " + entry.count + " items");
                if (sha256 != null) remember(context, sha256, entry.id);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Data package " + name + " failed", e);
                error = e.getMessage();
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                done(key);
            }
            done.finished(entry, error);
        }, "DataPackageDownload").start();
    }

    /** Lists the data packages on the TAK server. Blocking; call off the main thread. */
    static List<ServerPackage> queryServer(Context context) throws IOException {
        String url = apiBase(context) + "/Marti/sync/search?keywords=missionpackage";
        HttpURLConnection conn = open(context, url);
        String body;
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("server answered HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 10_000_000) throw new IOException("server list too large");
                }
                body = out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
        return parseServerList(body);
    }

    /** Parses /Marti/sync/search JSON: {"resultCount":n,"results":[{"Name","Hash",...}]}. */
    static List<ServerPackage> parseServerList(String json) throws IOException {
        List<ServerPackage> out = new ArrayList<>();
        try {
            org.json.JSONArray results = new org.json.JSONObject(json).optJSONArray("results");
            if (results == null) return out;
            for (int i = 0; i < results.length(); i++) {
                org.json.JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                ServerPackage p = new ServerPackage();
                p.hash = r.optString("Hash", r.optString("hash", ""));
                if (p.hash.isEmpty()) continue;
                p.name = r.optString("Name", r.optString("name", p.hash.substring(0, Math.min(8, p.hash.length()))));
                p.submitter = r.optString("SubmissionUser", r.optString("CreatorUid", ""));
                p.submitted = r.optString("SubmissionDateTime", "");
                p.size = r.optLong("Size", r.optLong("size", 0));
                out.add(p);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from server: " + e.getMessage(), e);
        }
        return out;
    }

    /** True if this package (by SHA-256) was imported and that import still exists. */
    static boolean isImported(Context context, String hash) {
        return hash != null && alreadyReceived(context, hash.toLowerCase(Locale.US));
    }

    private static String apiBase(Context context) throws IllegalStateException {
        String server = SettingsActivity.getTakServerAddress(context);
        if (server == null || server.isEmpty()) throw new IllegalStateException("No TAK server configured");
        return "https://" + server + ":" + API_PORT;
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

    /** A connection that presents the TAK client certificate for https URLs. */
    private static HttpURLConnection open(Context context, String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setSSLSocketFactory(mutualTls(context).getSocketFactory());
            // Same as TAK Lite's own server API calls: the server certificate must chain
            // to the TAK trust store, but the name it was issued for isn't checked.
            https.setHostnameVerifier((host, session) -> true);
        }
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        return conn;
    }

    private static void downloadOnce(Context context, String url, File dest) throws IOException {
        HttpURLConnection conn = open(context, url);
        try {
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
