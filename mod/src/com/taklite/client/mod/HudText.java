package com.taklite.client.mod;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Map labels drawn as outlined text instead of text on a dark box, as the
 * TAKPilot2 spec does for everything over imagery (section 4.3): legible on any
 * background while covering only the pixels around the glyphs.
 *
 * TAK Lite's marker-icon methods call these in place of Canvas.drawRoundRect and
 * Canvas.drawText (see patches/taklite-1.5.0.patch), with the same arguments.
 */
public final class HudText {

    private HudText() {
    }

    /**
     * Text with a black outline. The stroke is about an eighth of the text size:
     * it is centred on the glyph edge, so only its outer half shows, and a wider
     * one turns small text into blobs.
     */
    public static void drawOutlined(Canvas canvas, String text, float x, float y, Paint fill) {
        Paint outline = new Paint(fill);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setStrokeWidth(Math.max(1f, fill.getTextSize() / 8f));
        outline.setColor(Tak.HUD_OUTLINE);
        outline.setShadowLayer(0, 0, 0, 0);
        canvas.drawText(text, x, y, outline);
        canvas.drawText(text, x, y, fill);
    }

    /** Replaces the dark box TAK Lite drew behind each label: outlined text needs none. */
    public static void labelBackground(Canvas canvas, float left, float top, float right, float bottom,
                                       float rx, float ry, Paint paint) {
        // Intentionally empty.
    }
}
