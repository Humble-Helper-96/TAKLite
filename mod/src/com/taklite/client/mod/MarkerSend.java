package com.taklite.client.mod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.Toast;

import com.taklite.client.DroppedMarker;
import com.taklite.client.MainActivity;
import com.taklite.client.tak.TakManager;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sending markers to the team, and showing whether they went.
 *
 * Settings → "Send Marker Upon Placement". Off (default, stock TAK Lite's behaviour):
 * nothing goes out on its own; a marker is shared when the user asks, with Send on
 * its radial menu or Save & Send in the edit dialog. On: a marker is sent as soon as
 * it is placed, and again when saved after an edit or moved.
 *
 * Either way the user can see the result: every placement and send ends in a short
 * toast, and a marker that is on this phone only carries a small hollow ring at its
 * top right until it has been sent.
 */
public final class MarkerSend {

    private static final String TAG = "MarkerSend";
    private static final String KEY_SEND_ON_PLACE = "mod_send_on_place";

    /** Per map marker: {the icon as the stock code drew it, that icon with the "not sent" ring}. */
    private static final Map<Marker, Drawable[]> ringed = new WeakHashMap<>();

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
            MarkerPlace.placed();
            String message = autoSend(activity) ? send(activity, marker, "placed")
                    : "Marker placed. Tap it, then Send, to share it with the team.";
            refresh(activity, marker);
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not finish placing the marker", e);
        }
    }

    /**
     * Hook: the edit dialog's plain Save, after the marker is updated (Save & Send is
     * untouched and always sends). With the setting on the change goes to the team at
     * once; off, a marker the team already has gets a reminder to send it.
     */
    public static void onEdited(MainActivity activity, DroppedMarker marker) {
        try {
            if (marker.remote) return;
            String message = sendIfAuto(activity, marker, "updated");
            refresh(activity, marker);
            if (message != null) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not send the edited marker", e);
        }
    }

    /** Hook: the stock radial Send and Save & Send, right after they mark the marker as sent. */
    public static void onSent(MainActivity activity, DroppedMarker marker) {
        try {
            refresh(activity, marker);
            Toast.makeText(activity, "Marker sent to the team.", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not show the send result", e);
        }
    }

    /**
     * Hook and helper: makes the marker's icon match {@code marker.transmitted}: a hollow
     * ring at the top right while the marker is on this phone only, none once sent. Safe
     * to call any number of times, and after the stock code has redrawn the icon.
     */
    public static void refresh(MainActivity activity, DroppedMarker marker) {
        if (marker == null || marker.remote || marker.marker == null) return;
        Marker m = marker.marker;
        Drawable current = m.getIcon();
        Drawable[] pair = ringed.get(m);
        boolean showingRing = pair != null && current == pair[1];
        if (marker.transmitted) {
            if (showingRing) {
                m.setIcon(pair[0]);
                ringed.remove(m);
                invalidate(activity);
            }
            return;
        }
        if (showingRing || current == null) return;
        Drawable withRing = ring(activity, current);
        ringed.put(m, new Drawable[]{current, withRing});
        m.setIcon(withRing);
        invalidate(activity);
    }

    /** End of MainActivity.onCreate (the saved markers are loaded by then): ring every unsent one. */
    static void refreshAll(MainActivity activity) {
        try {
            for (DroppedMarker dm : activity.modDroppedMarkers().values()) refresh(activity, dm);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not mark unsent markers", e);
        }
    }

    private static void invalidate(MainActivity activity) {
        MapView map = ModUtil.mapView(activity);
        if (map != null) map.invalidate();
    }

    /** The icon with a small hollow ring (white, dark edge) at its top right; same size, so the anchor holds. */
    private static Drawable ring(Context context, Drawable base) {
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.max(1, base.getIntrinsicWidth());
        int h = Math.max(1, base.getIntrinsicHeight());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        base.setBounds(0, 0, w, h);
        base.draw(c);
        float r = 5 * density;
        float cx = w - r - 2 * density;
        float cy = r + 2 * density;
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(3.5f * density);
        edge.setColor(0xCC000000);
        c.drawCircle(cx, cy, r, edge);
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setStyle(Paint.Style.STROKE);
        white.setStrokeWidth(1.8f * density);
        white.setColor(Color.WHITE);
        c.drawCircle(cx, cy, r, white);
        return new BitmapDrawable(context.getResources(), bmp);
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
            return "Marker " + what + ", but you're not connected (see the top bar). Tap it and choose Send once it says Connected.";
        }
        MarkerIds.beforeSend(marker);
        tak.sendMarker(marker.position.getLatitude(), marker.position.getLongitude(),
                marker.position.getAltitude(), marker.affiliation, marker.name, marker.remarks);
        marker.transmitted = true;
        activity.modSaveDroppedMarkers();
        return "Marker " + what + " and sent to the team.";
    }
}
