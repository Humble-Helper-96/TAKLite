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

    /** Key of the marker about to be sent; set and consumed on the main thread. */
    private static String pendingKey;

    private MarkerIds() {
    }

    /** Hook: just before TakManager.sendMarker for this marker. */
    public static void beforeSend(DroppedMarker marker) {
        pendingKey = marker == null ? null : marker.key;
    }

    /**
     * Hook: inside TakManager.sendMarker. Returns this marker's stable uid, or
     * {@code fallback} (the stock random uid) for a send that didn't name a marker.
     */
    public static String uidForSend(String deviceUid, String fallback) {
        String key = pendingKey;
        pendingKey = null;
        if (key == null) return fallback;
        return stableUid(deviceUid, key);
    }

    /** Hook: TakManager.sendMarker, with the CoT about to go out (debug log only). */
    public static void logOutgoing(String cot) {
        android.util.Log.d("MarkerIds", "Marker CoT: " + cot);
    }

    /** Same device and marker key, same uid; different devices never collide. */
    static String stableUid(String deviceUid, String markerKey) {
        String seed = (deviceUid == null ? "" : deviceUid) + "|" + markerKey;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
