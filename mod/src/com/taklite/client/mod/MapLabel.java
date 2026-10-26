package com.taklite.client.mod;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Map labels in the TAK Aware style: white text on a solid black box with
 * barely rounded corners, readable on any imagery.
 *
 * TAK Lite's marker-icon methods call {@link #background} in place of
 * Canvas.drawRoundRect, with the same arguments (see patches/taklite-1.5.0.patch);
 * stock TAK Lite drew a 55% black box with rounder corners.
 */
public final class MapLabel {

    private static final int BOX = 0xF0000000;

    private MapLabel() {
    }

    public static void background(Canvas canvas, float left, float top, float right, float bottom,
                                  float rx, float ry, Paint paint) {
        Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        box.setColor(BOX);
        canvas.drawRoundRect(left, top, right, bottom, rx / 3f, ry / 3f, box);
    }
}
