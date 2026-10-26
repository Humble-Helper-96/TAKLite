package com.taklite.client.mod;

/**
 * Colour tokens from the TAKPilot2 UI specification (section 6.1), plus TAK
 * Aware's icon gold, so this app reads as part of the same kit as TAKPilot and
 * TAK Aware. Names follow the spec's {@code tp_*} tokens; values must stay
 * identical to it.
 */
public final class Tak {

    // Surfaces
    public static final int SURFACE_DIALOG = 0xFF202020;  // tp_surface_dialog
    public static final int SURFACE_FIELD = 0xFF2A2A2A;   // tp_surface_field
    public static final int SURFACE_ROW = 0xFF1A1F26;     // tp_surface_row
    public static final int BORDER = 0xFF333333;          // tp_border
    public static final int DIVIDER = 0x33FFFFFF;         // tp_divider

    // Text
    public static final int TEXT_PRIMARY = 0xFFFFFFFF;    // tp_text_primary
    public static final int TEXT_SECONDARY = 0xFFB0B0B0;  // tp_text_secondary
    public static final int TEXT_TERTIARY = 0xFF909090;   // tp_text_tertiary
    public static final int TEXT_HINT = 0xFF777777;       // tp_text_hint

    /** The one interactive colour: dialog actions, checkboxes, progress. */
    public static final int ACCENT = 0xFF9AC4FF;          // tp_accent

    // Button roles (section 6.2)
    public static final int BTN_PRIMARY = 0xFF2E7D32;     // tp_btn_primary: go
    public static final int BTN_INFO = 0xFF1565C0;        // tp_btn_info
    public static final int BTN_NEUTRAL = 0xFF37474F;     // tp_btn_neutral
    public static final int BTN_DANGER = 0xFFB71C1C;      // tp_btn_danger
    public static final int BTN_DANGER_DIALOG = 0xFFEF5350; // tp_btn_danger_dialog

    // States
    public static final int STATE_GO = 0xFF4CAF50;        // tp_state_go
    public static final int STATE_CAUTION = 0xFFFF9800;   // tp_state_caution: known, needs care
    public static final int STATE_DANGER = 0xFFF44336;    // tp_state_danger
    public static final int STATE_UNKNOWN = 0xFFFFB74D;   // tp_state_unknown: no answer yet

    // Map chrome
    public static final int HUD_CAPSULE = 0xB3000000;     // tp_hud_capsule (TAK Aware's overlay fill)
    /** TAK Aware's map-control icon gold. Estimated from screenshots; not in the TAKPilot spec. */
    public static final int AWARE_GOLD = 0xFFE6B400;

    private Tak() {
    }
}
