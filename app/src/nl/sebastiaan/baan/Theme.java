package nl.sebastiaan.baan;

import android.content.Context;
import android.graphics.Typeface;

/** Design tokens. Every colour on screen comes from here. */
final class Theme {
    static final String[] NAMES = {"Cyaan", "Amber", "Magenta", "Groen"};
    // accent, secondary, deep (dim accent), background top
    static final int[][] SETS = {
            {0xFF22E6FF, 0xFF7CF0C0, 0xFF0F8FA0, 0xFF06232A},
            {0xFFFFB020, 0xFFFFE08A, 0xFF9A6410, 0xFF2A1A04},
            {0xFFFF3DCB, 0xFF9B6BFF, 0xFF8A1F72, 0xFF240A22},
            {0xFF39FF88, 0xFFB8FF5C, 0xFF158A48, 0xFF06240F},
    };
    static final int BG = 0xFF020607;
    static final int TEXT = 0xFFE8FEFF;
    static final String[] SIZE_NAMES = {"Normaal", "Groot", "Extra groot"};
    static final float[] SIZE = {1f, 1.15f, 1.3f};

    static Typeface mono, monoBold;

    static void load(Context c) {
        if (mono != null) return;
        try {
            mono = Typeface.createFromAsset(c.getAssets(), "mono.ttf");
            monoBold = Typeface.createFromAsset(c.getAssets(), "monob.ttf");
        } catch (Exception e) {
            mono = Typeface.MONOSPACE;
            monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        }
    }

    static int idx(Context c) { return Math.min(SETS.length - 1, App.prefs(c).getInt("theme", 0)); }
    static int accent(Context c) { return SETS[idx(c)][0]; }
    static int second(Context c) { return SETS[idx(c)][1]; }
    static int deep(Context c) { return SETS[idx(c)][2]; }
    static int bgTop(Context c) { return SETS[idx(c)][3]; }
    static float size(Context c) { return SIZE[Math.min(2, App.prefs(c).getInt("size", 0))]; }

    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }
}
