package com.taklite.client.mod;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.taklite.client.DroppedMarker;
import com.taklite.client.MainActivity;
import com.taklite.client.tak.TakManager;

/**
 * Settings → "Send Marker Upon Placement".
 *
 * Off (default, stock TAK Lite's placement behaviour): nothing goes out on its own. A
 * placed, edited or moved marker is sent only from its radial menu's Send. On: a marker
 * is sent to the team as soon as it is placed, and again when edited or moved.
 *
 * Stock TAK Lite sent on every edit regardless; the edit hook makes that follow the
 * setting too, so "off" means off everywhere.
 */
public final class MarkerSend {

    private static final String TAG = "MarkerSend";
    private static final String KEY_SEND_ON_PLACE = "mod_send_on_place";

    private MarkerSend() {
    }

    public static boolean autoSend(Context context) {
        return ModUtil.prefs(context).getBoolean(KEY_SEND_ON_PLACE, false);
    }

    static void setAutoSend(Context context, boolean on) {
        ModUtil.prefs(context).edit().putBoolean(KEY_SEND_ON_PLACE, on).apply();
    }

    /** Hook: end of MainActivity.placeMarker, with the new marker. */
    public static void onPlaced(MainActivity activity, DroppedMarker marker) {
        try {
            if (autoSend(activity)) send(activity, marker, "placed");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not send the placed marker", e);
        }
    }

    /**
     * Hook: the edit dialog's Save, after the marker is updated and before the stock code
     * sends it. True skips the stock send (and its "Not connected" toast).
     */
    public static boolean skipStockSendAfterEdit(MainActivity activity, DroppedMarker marker) {
        try {
            return !autoSend(activity);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Sends {@code marker} under its stable uid if the setting is on and TAK is connected.
     * Returns a short status for a toast: null when nothing needed saying.
     */
    static String sendIfAuto(MainActivity activity, DroppedMarker marker, String what) {
        if (!autoSend(activity)) {
            return marker.transmitted ? "Marker " + what + ". Send it to update the team." : null;
        }
        return send(activity, marker, what);
    }

    private static String send(MainActivity activity, DroppedMarker marker, String what) {
        TakManager tak;
        try {
            tak = TakManager.getInstance();
        } catch (RuntimeException e) {
            tak = null;
        }
        if (tak == null || !tak.isConnected()) {
            String message = "Marker " + what + ". Not connected: send it when TAK is back.";
            if ("placed".equals(what)) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            return message;
        }
        MarkerIds.beforeSend(marker);
        tak.sendMarker(marker.position.getLatitude(), marker.position.getLongitude(),
                marker.position.getAltitude(), marker.affiliation, marker.name, marker.remarks);
        marker.transmitted = true;
        activity.modSaveDroppedMarkers();
        return "Marker " + what + " and sent to the team.";
    }
}
