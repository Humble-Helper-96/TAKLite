package com.taklite.client.mod.imports;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import com.taklite.client.MainActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Imports a KML, KMZ, GPX, CoT or data package file. Started three ways:
 * from Imported Data's "Import File" (shows the system file picker), and by
 * other apps through "Open with" (ACTION_VIEW) or "Share" (ACTION_SEND).
 * It has no screen of its own; progress and results are shown as toasts.
 */
public class ImportActivity extends Activity {

    private static final String TAG = "ImportActivity";
    private static final int PICK = 1;
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;

    private boolean fromOtherApp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Imports.init(this);
        if (savedInstanceState != null) return; // the picker result is still coming
        Intent intent = getIntent();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            fromOtherApp = true;
            importUri(uri);
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(pick, PICK);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importUri(data.getData());
        } else {
            finish();
        }
    }

    private void importUri(final Uri uri) {
        final String name = displayName(uri);
        Toast.makeText(this, "Importing " + name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            File tmp = new File(getCacheDir(), "import-" + System.nanoTime());
            try {
                copy(uri, tmp);
                ImportStore.Entry entry = Imports.importFile(tmp, name, "File");
                message = "Imported \"" + entry.name + "\": " + entry.count + " items on the map.";
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Import of " + name + " failed", e);
                message = "Could not import " + name + ": " + e.getMessage();
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            final String result = message;
            runOnUiThread(() -> {
                Toast.makeText(getApplicationContext(), result, Toast.LENGTH_LONG).show();
                if (fromOtherApp) {
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                finish();
            });
        }, "FileImport").start();
    }

    private void copy(Uri uri, File dest) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("could not open file");
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_FILE_BYTES) throw new IOException("file is larger than 200 MB");
                out.write(buf, 0, n);
            }
        } catch (SecurityException e) {
            throw new IOException("no permission to read the file", e);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException e) {
            // Fall through to the URI's last segment.
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file";
    }
}
