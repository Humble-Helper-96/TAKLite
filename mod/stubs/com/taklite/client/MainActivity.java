package com.taklite.client;

/**
 * Compile-time stub only; never packaged. The real class is the stock app's
 * MainActivity, and these methods are added to it by patches/taklite-1.5.0.patch.
 */
public class MainActivity extends android.app.Activity {

    /** Selects a map type, saves it as the "map_type" preference and redraws the map. */
    public void modSetMapType(int type, boolean announce) {
        throw new UnsupportedOperationException("stub");
    }

    /** TAK Lite's teammate marker icon (iconset or MIL-STD-2525 symbol, label underneath). */
    public android.graphics.drawable.Drawable modMarkerIcon(String label, String cotType, String iconsetPath) {
        throw new UnsupportedOperationException("stub");
    }

    /** Turns off "follow my location" so the map can be moved elsewhere. */
    public void modStopFollowing() {
        throw new UnsupportedOperationException("stub");
    }

    /** Closes the marker / user radial menu if one is open. */
    public void modDismissRadialMenu() {
        throw new UnsupportedOperationException("stub");
    }

    /** Persists the dropped markers (stock saveDroppedMarkers). */
    public void modSaveDroppedMarkers() {
        throw new UnsupportedOperationException("stub");
    }
}
