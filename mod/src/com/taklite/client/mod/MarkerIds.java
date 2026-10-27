package com.taklite.client.mod;

import com.taklite.client.DroppedMarker;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stable CoT uids for dropped markers.
 *
 * Stock TAK Lite gives a marker a new random uid every time it is sent, so each
 * re-send (after an edit or a move) adds another copy on teammates' maps. The
 * patched send paths call {@link #beforeSend} with the marker, and
 * TakManager.sendMarker asks {@link #uidForSend} for the uid: the same marker
 * then always goes out under the same uid, which TAK clients treat as an update.
 */
public final class MarkerIds {

    /**
     * Key of the marker about to be sent. sendMarker returns early when TAK is offline,
     * leaving it unused, so it only counts for the very next send on the same thread.
     */
    private static String pendingKey;
    private static Thread pendingThread;
    private static long pendingAt;
    private static final long PENDING_MS = 2000;

    private MarkerIds() {
    }

    /** Hook: just before TakManager.sendMarker for this marker. */
    public static synchronized void beforeSend(DroppedMarker marker) {
        pendingKey = marker == null ? null : marker.key;
        pendingThread = Thread.currentThread();
        pendingAt = android.os.SystemClock.elapsedRealtime();
    }

    /**
     * Hook: inside TakManager.sendMarker. Returns this marker's stable uid, or
     * {@code fallback} (the stock random uid) for a send that didn't name a marker.
     */
    public static synchronized String uidForSend(String deviceUid, String fallback) {
        String key = pendingKey;
        boolean fresh = pendingThread == Thread.currentThread()
                && android.os.SystemClock.elapsedRealtime() - pendingAt < PENDING_MS;
        pendingKey = null;
        pendingThread = null;
        if (key == null || !fresh) return fallback;
        return stableUid(deviceUid, key);
    }

    /**
     * Hook: TakManager.sendMarker, with the CoT about to go out. Logged only when turned on
     * with {@code adb shell setprop log.tag.MarkerIds DEBUG}; TAK Lite already logs each send.
     */
    public static void logOutgoing(String cot) {
        if (android.util.Log.isLoggable("MarkerIds", android.util.Log.DEBUG)) {
            android.util.Log.d("MarkerIds", "Marker CoT: " + cot);
        }
    }

    /** Same device and marker key, same uid; different devices never collide. */
    static String stableUid(String deviceUid, String markerKey) {
        String seed = (deviceUid == null ? "" : deviceUid) + "|" + markerKey;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
