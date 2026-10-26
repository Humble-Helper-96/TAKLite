package com.taklite.client.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.modules.SqlTileWriter;

import java.io.File;
import java.util.Locale;

/**
 * Size limit of osmdroid's on-phone tile cache (one SQLite file shared by all maps).
 *
 * osmdroid keeps that limit only in memory and trims the cache when the map
 * starts, so {@link #apply} has to run before MainActivity creates its MapView.
 */
final class TileCache {

    private static final String PREFS = "TAKLitePrefs";
    private static final String KEY_LIMIT_MB = "custom_map_cache_mb";
    /** osmdroid's own default. */
    static final int DEFAULT_LIMIT_MB = 600;
    private static final int[] LIMIT_CHOICES_MB = {300, 600, 1024, 2048, 4096, 8192, 16384};
    private static final long MB = 1024L * 1024L;

    private TileCache() {
    }

    static int limitMb(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LIMIT_MB, DEFAULT_LIMIT_MB);
    }

    /**
     * Pushes the chosen limit into osmdroid. Like osmdroid itself, never lets the cache
     * grow past 95% of the space it could have, and trims it to 5/6 of the limit
     * (osmdroid's 500 MB / 600 MB ratio) when it overflows.
     */
    static void apply(Context context) {
        long max = Math.min(limitMb(context) * MB, (long) (availableBytes(context) * 0.95));
        IConfigurationProvider config = Configuration.getInstance();
        config.setTileFileSystemCacheMaxBytes(max);
        config.setTileFileSystemCacheTrimBytes(max / 6 * 5);
    }

    static long usedBytes(Context context) {
        return new File(cacheDir(context), SqlTileWriter.DATABASE_FILENAME).length();
    }

    static long maxBytes() {
        return Configuration.getInstance().getTileFileSystemCacheMaxBytes();
    }

    /** Free space plus what the cache already uses: the most the cache could grow to. */
    private static long availableBytes(Context context) {
        File dir = cacheDir(context);
        return dir.getUsableSpace() + usedBytes(context);
    }

    private static File cacheDir(Context context) {
        // Same lookup as osmdroid's Configuration.load(), which stores the path it picked.
        SharedPreferences osmPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        String path = osmPrefs.getString("osmdroid.cachePath", null);
        return path != null ? new File(path) : Configuration.getInstance().getOsmdroidTileCache(context);
    }

    static String summary(Context context) {
        File dir = cacheDir(context);
        return "Using " + formatSize(usedBytes(context)) + " of " + formatSize(maxBytes())
                + " · " + formatSize(dir.getUsableSpace()) + " free on this phone. "
                + "Shared by all maps; when it fills up, the oldest tiles are removed.";
    }

    static String formatSize(long bytes) {
        if (bytes >= 1024 * MB) {
            double gb = bytes / (1024.0 * MB);
            return gb >= 10 || gb == Math.floor(gb)
                    ? String.format(Locale.US, "%.0f GB", gb)
                    : String.format(Locale.US, "%.1f GB", gb);
        }
        return Math.max(0, Math.round(bytes / (double) MB)) + " MB";
    }

    /** Confirms, then deletes every cached tile (all maps) and reopens via {@code next}. */
    static void confirmClear(final Context context, final Runnable next) {
        if (AreaDownloader.isRunning()) {
            Toast.makeText(context, "Wait for the map download to finish, or cancel it, first.",
                    Toast.LENGTH_LONG).show();
            next.run();
            return;
        }
        final DarkDialog d = new DarkDialog(context, "Clear Offline Cache?");
        d.addText("Deletes all " + formatSize(usedBytes(context)) + " of saved map tiles, for every "
                + "map. Areas you downloaded for offline use will need downloading again. Tiles are "
                + "saved again as you use the map.");
        d.addAction("CANCEL", v -> {
            d.dismiss();
            next.run();
        });
        d.addDestructiveAction("CLEAR", v -> {
            d.dismiss();
            final Context app = context.getApplicationContext();
            final long before = usedBytes(context);
            Toast.makeText(context, "Clearing the map cache\u2026", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String message;
                try {
                    new Cleaner().clearAll();
                    message = "Map cache cleared: freed " + formatSize(Math.max(0, before - usedBytes(app))) + ".";
                } catch (RuntimeException e) {
                    Log.e("TileCache", "Could not clear the map cache", e);
                    message = "Could not clear the map cache: " + e.getMessage();
                }
                final String result = message;
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(app, result, Toast.LENGTH_LONG).show();
                    next.run();
                });
            }, "ClearTileCache").start();
        });
        d.show();
    }

    /**
     * osmdroid's purgeCache() only deletes rows, so the file keeps its size. VACUUM
     * afterwards gives the space back. Uses osmdroid's own (shared) database connection.
     */
    private static final class Cleaner extends SqlTileWriter {
        void clearAll() {
            if (!purgeCache()) throw new IllegalStateException("tile database is not available");
            SQLiteDatabase db = getDb();
            if (db != null && db.isOpen()) db.execSQL("VACUUM");
        }
    }

    static void showLimitPicker(final Context context, final Runnable next) {
        final DarkDialog d = new DarkDialog(context, "Offline Cache Size");
        d.dialog.setOnDismissListener(dialog -> next.run());
        d.addText("How much space map tiles may use on this phone. Lowering it removes "
                + "tiles the next time the map opens.");
        long available = availableBytes(context);
        int current = limitMb(context);
        for (final int mb : LIMIT_CHOICES_MB) {
            String title = formatSize(mb * MB) + (mb == DEFAULT_LIMIT_MB ? " (default)" : "");
            String subtitle = mb * MB > available * 0.95
                    ? "More than this phone has free; limited to " + formatSize((long) (available * 0.95))
                    : null;
            d.addRow(mb == current ? title + "  ✓" : title, subtitle,
                    mb == current ? Tak.STATE_GO : Color.WHITE, v -> {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putInt(KEY_LIMIT_MB, mb).apply();
                        apply(context);
                        d.dismiss();
                    });
        }
        d.addAction("CANCEL", v -> d.dismiss());
        d.show();
    }
}
