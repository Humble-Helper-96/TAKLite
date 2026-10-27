package com.taklite.client.mod;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A one-line bar under the top bar that says what the next map tap will do, with a ✕
 * to stop. Used while placing and while moving a marker, so the user is never in a
 * mode the screen doesn't show.
 *
 * The bar sits in a full-screen, touch-transparent holder of our own: the window's
 * content view can't be relied on to honour gravity and margins.
 */
final class HintBar {

    private final FrameLayout holder;

    private HintBar(FrameLayout holder) {
        this.holder = holder;
    }

    /** Shows {@code text} with a ✕ that runs {@code onCancel}. */
    static HintBar show(Activity activity, String text, Runnable onCancel) {
        FrameLayout holder = new FrameLayout(activity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, DarkDialog.dp(activity, 44),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = belowToolbar(activity);
        holder.addView(build(activity, text, onCancel), lp);
        activity.getWindow().addContentView(holder, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return new HintBar(holder);
    }

    void hide() {
        if (holder.getParent() instanceof ViewGroup) ((ViewGroup) holder.getParent()).removeView(holder);
    }

    /** Just under TAK Lite's top bar, or 72 dp down if it can't be found. */
    private static int belowToolbar(Activity activity) {
        int id = activity.getResources().getIdentifier("toolbar", "id", activity.getPackageName());
        View toolbar = id == 0 ? null : activity.findViewById(id);
        int bottom = toolbar == null ? 0 : toolbar.getBottom();
        return (bottom > 0 ? bottom : DarkDialog.dp(activity, 64)) + DarkDialog.dp(activity, 8);
    }

    private static View build(Activity activity, String message, Runnable onCancel) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(DarkDialog.dp(activity, 14), 0, DarkDialog.dp(activity, 2), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Tak.HUD_CAPSULE);
        bg.setCornerRadius(DarkDialog.dp(activity, 22));
        bar.setBackground(bg);
        bar.setElevation(DarkDialog.dp(activity, 6));

        TextView text = new TextView(activity);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Never wider than the screen allows, whatever the marker is called.
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        text.setMaxWidth(screen - DarkDialog.dp(activity, 14 + 40 + 2 + 32));
        bar.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Cancel");
        close.setOnClickListener(v -> onCancel.run());
        bar.addView(close, new LinearLayout.LayoutParams(DarkDialog.dp(activity, 40), ViewGroup.LayoutParams.MATCH_PARENT));
        return bar;
    }
}
