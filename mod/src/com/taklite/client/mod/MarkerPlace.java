package com.taklite.client.mod;

import android.util.Log;

import com.taklite.client.MainActivity;

import java.lang.ref.WeakReference;

/**
 * Placing a marker: after the user picks Friendly / Hostile / Unknown / Neutral from
 * the + popup, stock TAK Lite waits silently for the next map tap. This shows a hint
 * bar ("Tap the map to place the Friendly marker  ✕") until the marker is placed or
 * the ✕ is tapped, so the waiting state is visible and can be left.
 */
public final class MarkerPlace {

    private static final String TAG = "MarkerPlace";

    private static HintBar bar;
    private static WeakReference<MainActivity> armedOn = new WeakReference<>(null);

    private MarkerPlace() {
    }

    /** Hook: the + popup, right after the affiliation was chosen. */
    public static void armed(final MainActivity activity, String affiliation) {
        try {
            MarkerMove.cancel(); // one hint bar at a time
            hideBar();
            armedOn = new WeakReference<>(activity);
            String what = affiliation == null || affiliation.isEmpty() ? "marker" : affiliation + " marker";
            bar = HintBar.show(activity, "Tap the map to place the " + what, () -> cancel(activity));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not show the placement hint", e);
        }
    }

    /** The marker was placed (any way the stock code places one). */
    static void placed() {
        hideBar();
        armedOn = new WeakReference<>(null);
    }

    /** ✕ on the bar, or the map screen going away: nothing is placed on the next tap. */
    public static void cancel(MainActivity activity) {
        MainActivity target = armedOn.get();
        hideBar();
        armedOn = new WeakReference<>(null);
        if (target == null || (activity != null && target != activity)) return;
        try {
            target.modCancelPlacement();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not cancel the placement", e);
        }
    }

    private static void hideBar() {
        if (bar != null) {
            bar.hide();
            bar = null;
        }
    }
}
