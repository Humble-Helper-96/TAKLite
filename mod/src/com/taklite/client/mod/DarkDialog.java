package com.taklite.client.mod;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Dialogs in the TAKPilot2 style (spec section 6): a dialog surface with a
 * hairline border, actions in the one accent colour (destructive ones in red),
 * and full-width buttons that carry a colour role (go, neutral, info, danger).
 */
public final class DarkDialog {

    private static final int RADIUS_DP = 12;

    /**
     * The colour names the feature code (shared with the 1.5.0 branch) uses, mapped onto
     * the TAKPilot tokens so that code compiles here unchanged and still comes out in this
     * theme: see {@link #addAction(String, int, View.OnClickListener)} and
     * {@link #addButton}, which translate them to roles rather than painting the raw values.
     */
    public static final int COLOR_ACCENT = Tak.STATE_GO;
    public static final int COLOR_DANGER = Tak.STATE_DANGER;
    public static final int COLOR_LABEL = Tak.TEXT_SECONDARY;
    public static final int COLOR_FIELD = Tak.BTN_NEUTRAL;
    public static final int COLOR_PRIMARY = Tak.SURFACE_DIALOG;
    public static final int COLOR_HINT = Tak.TEXT_HINT;
    public static final int COLOR_SECONDARY = Tak.TEXT_SECONDARY;

    public final Context context;
    public final Dialog dialog;
    public final LinearLayout content;
    private final LinearLayout buttonBar;

    public DarkDialog(Context context, String title) {
        this.context = context;
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(rounded(Tak.SURFACE_DIALOG, Tak.BORDER, dp(RADIUS_DP)));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Tak.TEXT_PRIMARY);
        titleView.setPadding(dp(20), dp(18), dp(20), dp(6));
        root.addView(titleView, matchWrap());

        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(10), dp(20), dp(10));
        scroll.addView(content, matchWrap());
        // Weighted so a long list scrolls and the action bar stays on screen.
        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        buttonBar = new LinearLayout(context);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttonBar.setPadding(dp(12), dp(4), dp(12), dp(10));
        root.addView(buttonBar, matchWrap());

        dialog.setContentView(root);
    }

    public void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    public void dismiss() {
        dialog.dismiss();
    }

    /** A text action in the bottom bar, in the accent colour. */
    public TextView addAction(String label, View.OnClickListener listener) {
        return addAction(label, Tak.ACCENT, listener);
    }

    /** A destructive text action (delete, stop), in the dialog danger red. */
    public TextView addDestructiveAction(String label, View.OnClickListener listener) {
        return addAction(label, Tak.BTN_DANGER_DIALOG, listener);
    }

    /**
     * A text action with a colour hint. In this theme actions are either the accent or,
     * for destructive ones, the dialog red, so the hint only decides between those two.
     */
    public TextView addAction(String label, int color, View.OnClickListener listener) {
        boolean destructive = color == COLOR_DANGER || color == Tak.BTN_DANGER || color == Tak.BTN_DANGER_DIALOG;
        TextView action = new TextView(context);
        action.setText(label);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setTextColor(destructive ? Tak.BTN_DANGER_DIALOG : Tak.ACCENT);
        action.setPadding(dp(12), dp(10), dp(12), dp(10));
        action.setClickable(true);
        action.setFocusable(true);
        action.setBackgroundResource(selectableBackground(context));
        action.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(2));
        buttonBar.addView(action, lp);
        return action;
    }

    /** Pushes the actions added after this call to the right, leaving earlier ones on the left. */
    public void addActionSpacer() {
        buttonBar.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    public TextView addLabel(String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(Tak.TEXT_TERTIARY);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = content.getChildCount() == 0 ? 0 : dp(14);
        lp.bottomMargin = dp(4);
        content.addView(label, lp);
        return label;
    }

    public TextView addText(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(Tak.TEXT_SECONDARY);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(8);
        content.addView(view, lp);
        return view;
    }

    public EditText addField(String label, String hint, String value, int inputType) {
        addLabel(label);
        EditText field = new EditText(context);
        field.setTextColor(Tak.TEXT_PRIMARY);
        field.setHintTextColor(Tak.TEXT_HINT);
        field.setBackground(rounded(Tak.SURFACE_FIELD, Tak.BORDER, dp(6)));
        field.setPadding(dp(12), dp(12), dp(12), dp(12));
        field.setHint(hint);
        field.setInputType(inputType);
        if ((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) field.setSingleLine(true);
        if (value != null) field.setText(value);
        content.addView(field, matchWrap());
        return field;
    }

    /** A full-width button; {@code role} is one of the Tak.BTN_* colours (or a COLOR_* name, translated). */
    public Button addButton(String label, int role, View.OnClickListener listener) {
        if (role == COLOR_ACCENT) role = Tak.BTN_PRIMARY;
        else if (role == COLOR_DANGER) role = Tak.BTN_DANGER;
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Tak.TEXT_PRIMARY);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                rounded(role, 0, dp(8)), null));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(8);
        content.addView(button, lp);
        return button;
    }

    /** A tappable two-line row (title + subtitle) for lists. */
    public LinearLayout addRow(String title, String subtitle, int titleColor, View.OnClickListener listener) {
        LinearLayout row = row(context, title, subtitle, titleColor, listener);
        content.addView(row, matchWrap());
        return row;
    }

    /** The view behind {@link #addRow}, for lists built outside a dialog's content. Child 0 is the title. */
    public static LinearLayout row(Context context, String title, String subtitle, int titleColor,
                                   View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(selectableBackground(context));
        row.setOnClickListener(listener);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(titleColor);
        row.addView(titleView, matchWrap());

        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = new TextView(context);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(Tak.TEXT_TERTIARY);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(sub, matchWrap());
        }
        return row;
    }

    public void addDivider() {
        View line = new View(context);
        line.setBackgroundColor(Tak.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(8);
        content.addView(line, lp);
    }

    public int dp(int value) {
        return dp(context, value);
    }

    public static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Rounded fill with an optional 1dp border ({@code stroke} 0 for none). */
    private Drawable rounded(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private static int selectableBackground(Context context) {
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }
}
