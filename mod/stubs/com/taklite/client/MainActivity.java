package com.taklite.client;

/**
 * Compile-time stub only; never packaged. The real class is the stock app's
 * MainActivity, and modSetMapType is added to it by patches/taklite-1.5.0.patch.
 */
public class MainActivity extends android.app.Activity {

    /** Selects a map type, saves it as the "map_type" preference and redraws the map. */
    public void modSetMapType(int type, boolean announce) {
        throw new UnsupportedOperationException("stub");
    }
}
