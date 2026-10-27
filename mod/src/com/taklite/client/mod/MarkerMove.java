package com.taklite.client.mod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.Toast;

import com.taklite.client.DroppedMarker;
import com.taklite.client.MainActivity;
import com.taklite.client.RadialMenuView;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;

/**
 * "Move" on a dropped marker's radial menu: tap the marker, tap Move, tap the
 * map where it should go. With "Send Marker Upon Placement" on, the marker is
 * sent again under the same uid (see {@link MarkerIds}), so teammates see it move.
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

    /** Ends move mode, if a marker is being moved (also when the map screen is paused). */
    public static void cancel() {
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
        final HintBar hint;

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
            String name = marker.name == null || marker.name.isEmpty() ? "the marker" : "\"" + marker.name + "\"";
            hint = HintBar.show(activity, "Tap the map to move " + name, MarkerMove::cancel);
        }

        private void moveTo(GeoPoint p) {
            marker.position.setCoords(p.getLatitude(), p.getLongitude());
            marker.marker.setPosition(new GeoPoint(p.getLatitude(), p.getLongitude(), marker.position.getAltitude()));
            map.invalidate();
            activity.modSaveDroppedMarkers();
            // Sent again only with "Send Marker Upon Placement" on; otherwise the user sends it.
            String message = MarkerSend.sendIfAuto(activity, marker, "moved");
            MarkerSend.refresh(activity, marker);
            Toast.makeText(activity, message != null ? message : "Marker moved.", Toast.LENGTH_SHORT).show();
            cancel();
        }

        void finish() {
            map.getOverlays().remove(tapCatcher);
            hint.hide();
            map.invalidate();
        }
    }
}
