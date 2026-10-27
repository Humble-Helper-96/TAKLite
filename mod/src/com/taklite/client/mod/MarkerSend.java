package com.taklite.client.mod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
 * toast (the radial menu's Send slice is a paper-plane icon, so the toasts say
 * "the paper plane"), and once a marker has been sent the same paper plane is drawn
 * inside its symbol; a marker that is on this phone only has an empty symbol. The
 * plane sits on the symbol, which the stock icon centres at the top, so its place
 * doesn't depend on how long the label is.
 */
public final class MarkerSend {

    private static final String TAG = "MarkerSend";
    private static final String KEY_SEND_ON_PLACE = "mod_send_on_place";

    /** Per map marker: {the icon as the stock code drew it, that icon with the "sent" plane}. */
    private static final Map<Marker, Drawable[]> planed = new WeakHashMap<>();

    /** The stock symbol size in createDroppedMarkerIcon / createTakMarkerIcon, in dp. */
    private static final int SYMBOL_DP = 32;

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
                    : "Marker placed. Tap it, then the paper plane, to share it with the team.";
            refresh(activity, marker);
            toast(activity, message);
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
            if (message != null) toast(activity, message);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not send the edited marker", e);
        }
    }

    /** Hook: the stock radial Send and Save & Send, right after they mark the marker as sent. */
    public static void onSent(MainActivity activity, DroppedMarker marker) {
        try {
            refresh(activity, marker);
            toast(activity, "Marker sent to the team.");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not show the send result", e);
        }
    }

    /**
     * Hook and helper: makes the marker's icon match {@code marker.transmitted}: a paper
     * plane inside the symbol once sent, the plain symbol while the marker is on this
     * phone only. Safe to call any number of times, and after the stock code has redrawn
     * the icon (the plane is then drawn again on the new icon).
     */
    public static void refresh(MainActivity activity, DroppedMarker marker) {
        if (marker == null || marker.remote || marker.marker == null) return;
        Marker m = marker.marker;
        Drawable current = m.getIcon();
        Drawable[] pair = planed.get(m);
        boolean showingPlane = pair != null && current == pair[1];
        if (!marker.transmitted) {
            if (showingPlane) {
                m.setIcon(pair[0]);
                planed.remove(m);
                invalidate(activity);
            }
            return;
        }
        if (showingPlane || current == null) return;
        Drawable withPlane = withPlane(activity, current);
        planed.put(m, new Drawable[]{current, withPlane});
        m.setIcon(withPlane);
        invalidate(activity);
    }

    /** End of MainActivity.onCreate (the saved markers are loaded by then): mark every sent one. */
    static void refreshAll(MainActivity activity) {
        try {
            for (DroppedMarker dm : activity.modDroppedMarkers().values()) refresh(activity, dm);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not mark sent markers", e);
        }
    }

    /** A toast that stays long enough to read: longer messages get the long duration. */
    static void toast(Context context, String message) {
        Toast.makeText(context, message, message.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    private static void invalidate(MainActivity activity) {
        MapView map = ModUtil.mapView(activity);
        if (map != null) map.invalidate();
    }

    /**
     * The icon with the paper plane drawn inside its symbol; same size, so the anchor
     * holds. The stock icon puts a 32 dp symbol at the top, centred, with the label box
     * under it, so the plane goes at (width / 2, 16 dp) whatever the label measures.
     */
    private static Drawable withPlane(Context context, Drawable base) {
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.max(1, base.getIntrinsicWidth());
        int h = Math.max(1, base.getIntrinsicHeight());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        base.setBounds(0, 0, w, h);
        base.draw(c);
        float cx = w / 2f;
        float cy = Math.min(h / 2f, SYMBOL_DP * density / 2f);
        int size = Math.round(16 * density);
        Drawable plane = planeDrawable(context);
        if (plane != null) {
            drawGlyph(c, plane, cx, cy, size, density);
        } else {
            drawPlanePath(c, cx, cy, size, density);
        }
        return new BitmapDrawable(context.getResources(), bmp);
    }

    /** The radial menu's Send icon, so the plane on the marker is the one the user taps. */
    private static Drawable planeDrawable(Context context) {
        try {
            int id = context.getResources().getIdentifier("radial_send", "drawable", context.getPackageName());
            if (id == 0) return null;
            Drawable d = context.getResources().getDrawable(id, context.getTheme());
            return d == null ? null : d.mutate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Draws {@code glyph} white with a thin dark halo, so it reads on any symbol colour. */
    private static void drawGlyph(Canvas c, Drawable glyph, float cx, float cy, int size, float density) {
        int left = Math.round(cx - size / 2f);
        int top = Math.round(cy - size / 2f);
        int o = Math.max(1, Math.round(density));
        glyph.setColorFilter(new PorterDuffColorFilter(0xE6000000, PorterDuff.Mode.SRC_IN));
        int[][] offsets = {{-o, 0}, {o, 0}, {0, -o}, {0, o}, {-o, -o}, {o, -o}, {-o, o}, {o, o}};
        for (int[] d : offsets) {
            glyph.setBounds(left + d[0], top + d[1], left + d[0] + size, top + d[1] + size);
            glyph.draw(c);
        }
        glyph.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        glyph.setBounds(left, top, left + size, top + size);
        glyph.draw(c);
    }

    /** A plain paper plane, for the unlikely case that the stock icon can't be loaded. */
    private static void drawPlanePath(Canvas c, float cx, float cy, int size, float density) {
        float s = size / 2f;
        Path p = new Path();
        p.moveTo(cx - s, cy - s * 0.6f);   // tail, top left
        p.lineTo(cx + s, cy);              // nose
        p.lineTo(cx - s, cy + s * 0.6f);   // tail, bottom left
        p.lineTo(cx - s * 0.4f, cy);       // notch
        p.close();
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2.5f * density);
        edge.setStrokeJoin(Paint.Join.ROUND);
        edge.setColor(0xE6000000);
        c.drawPath(p, edge);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.WHITE);
        c.drawPath(p, fill);
    }

    /**
     * Sends {@code marker} under its stable uid if the setting is on and TAK is connected.
     * Returns a short status for a toast: null when nothing needed saying.
     */
    static String sendIfAuto(MainActivity activity, DroppedMarker marker, String what) {
        if (!autoSend(activity)) {
            return marker.transmitted ? "Marker " + what + ". Tap it, then the paper plane, to update the team." : null;
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
            return "Marker " + what + ", but not connected. Tap it, then the paper plane, once the top bar says Connected.";
        }
        MarkerIds.beforeSend(marker);
        tak.sendMarker(marker.position.getLatitude(), marker.position.getLongitude(),
                marker.position.getAltitude(), marker.affiliation, marker.name, marker.remarks);
        marker.transmitted = true;
        activity.modSaveDroppedMarkers();
        return "Marker " + what + " and sent to the team.";
    }
}
