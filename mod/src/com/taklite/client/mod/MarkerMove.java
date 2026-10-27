package com.taklite.client.mod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.taklite.client.DroppedMarker;
import com.taklite.client.MainActivity;
import com.taklite.client.RadialMenuView;
import com.taklite.client.tak.TakManager;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;

/**
 * "Move" on a dropped marker's radial menu: tap the marker, tap Move, tap the
 * map where it should go. A marker that was already sent to TAK is sent again
 * under the same uid (see {@link MarkerIds}), so teammates see it move.
 */
public final class MarkerMove {

    /** The move waiting for its map tap, if any. */
    private static Pending pending;

    private MarkerMove() {
    }

    /** Hook: end of MainActivity.showRadialMenu(marker), with the menu just shown. */
    public static void attach(final MainActivity activity, View radialMenu, final DroppedMarker marker) {
        if (!(radialMenu instanceof RadialMenuView)) return;
        ((RadialMenuView) radialMenu).modMove = () -> {
            activity.modDismissRadialMenu();
            start(activity, marker);
        };
    }

    /** Hook: the radial menu's icon for the Move slice: a circle with an arrow. */
    public static Bitmap radialIcon(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.max(1, (int) (20 * density));
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.09f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float mid = size / 2f;
        c.drawCircle(size * 0.22f, mid, size * 0.16f, p);
        c.drawLine(size * 0.48f, mid, size * 0.93f, mid, p);
        c.drawLine(size * 0.93f, mid, size * 0.74f, mid - size * 0.2f, p);
        c.drawLine(size * 0.93f, mid, size * 0.74f, mid + size * 0.2f, p);
        return bmp;
    }

    private static void start(MainActivity activity, DroppedMarker marker) {
        cancel();
        if (marker.remote) {
            Toast.makeText(activity, "This marker came from another TAK user; only they can move it.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        MapView map = ModUtil.mapView(activity);
        if (map == null) return;
        pending = new Pending(activity, map, marker);
    }

    /** Ends move mode, if a marker is being moved. */
    static void cancel() {
        if (pending != null) {
            pending.finish();
            pending = null;
        }
    }

    /** Move mode: a hint bar with a cancel ✕, and an overlay that takes the next map tap. */
    private static final class Pending {
        final MainActivity activity;
        final MapView map;
        final DroppedMarker marker;
        final MapEventsOverlay tapCatcher;
        final View hint;

        Pending(MainActivity activity, MapView map, DroppedMarker marker) {
            this.activity = activity;
            this.map = map;
            this.marker = marker;
            tapCatcher = new MapEventsOverlay(new MapEventsReceiver() {
                @Override
                public boolean singleTapConfirmedHelper(GeoPoint p) {
                    moveTo(p);
                    return true;
                }

                @Override
                public boolean longPressHelper(GeoPoint p) {
                    return true; // swallowed while moving, so it can't start something else
                }
            });
            // Last overlay = on top: it gets the tap before markers and the drop-marker handler.
            map.getOverlays().add(tapCatcher);
            hint = buildHint();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = DarkDialog.dp(activity, 72);
            activity.getWindow().addContentView(hint, lp);
        }

        private View buildHint() {
            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            int pad = DarkDialog.dp(activity, 14);
            bar.setPadding(pad, DarkDialog.dp(activity, 4), DarkDialog.dp(activity, 4), DarkDialog.dp(activity, 4));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE6202020);
            bg.setCornerRadius(DarkDialog.dp(activity, 22));
            bar.setBackground(bg);
            bar.setElevation(DarkDialog.dp(activity, 6));

            TextView text = new TextView(activity);
            String name = marker.name == null || marker.name.isEmpty() ? "the marker" : "\"" + marker.name + "\"";
            text.setText("Tap the map to move " + name);
            text.setTextColor(Color.WHITE);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            bar.addView(text);

            TextView close = new TextView(activity);
            close.setText("✕");
            close.setTextColor(Color.WHITE);
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            close.setTypeface(Typeface.DEFAULT_BOLD);
            close.setGravity(Gravity.CENTER);
            close.setContentDescription("Cancel move");
            int touch = DarkDialog.dp(activity, 40);
            close.setOnClickListener(v -> cancel());
            bar.addView(close, new LinearLayout.LayoutParams(touch, touch));
            return bar;
        }

        private void moveTo(GeoPoint p) {
            marker.position.setCoords(p.getLatitude(), p.getLongitude());
            marker.marker.setPosition(new GeoPoint(p.getLatitude(), p.getLongitude(), marker.position.getAltitude()));
            map.invalidate();
            activity.modSaveDroppedMarkers();
            String message = "Marker moved.";
            if (marker.transmitted) {
                TakManager tak = TakManager.getInstance();
                if (tak.isConnected()) {
                    MarkerIds.beforeSend(marker);
                    tak.sendMarker(marker.position.getLatitude(), marker.position.getLongitude(),
                            marker.position.getAltitude(), marker.affiliation, marker.name, marker.remarks);
                    message = "Marker moved and sent to the team.";
                } else {
                    message = "Marker moved. Not connected: send it again to update the team.";
                }
            }
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            cancel();
        }

        void finish() {
            map.getOverlays().remove(tapCatcher);
            if (hint.getParent() instanceof ViewGroup) ((ViewGroup) hint.getParent()).removeView(hint);
            map.invalidate();
        }
    }
}
