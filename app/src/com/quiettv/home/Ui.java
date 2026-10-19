package com.quiettv.home;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.widget.TextView;

/** QuietTV's look in one place: colors, the background, icons and text styles. */
final class Ui {
    static final int TEXT = 0xFFF2F5FA;        // primary text
    static final int MUTED = 0xA6DCE3EE;       // secondary text
    static final int FAINT = 0x5CDCE3EE;       // hints
    static final int ACCENT = 0xFF5EEAD4;      // teal, from the logo
    static final int CARD = 0x14FFFFFF;        // resting card: white at 8%
    static final int CARD_EDGE = 0x1AFFFFFF;   // its hairline border
    static final int FOCUS_BG = 0xFFF2F5FA;    // a focused chip or card turns white...
    static final int FOCUS_FG = 0xFF0B0F17;    // ...with dark text and icon

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Deep navy with two faint glows (teal behind the clock, indigo low on the right). Drawn once. */
    static Drawable background(Context c) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF111827, 0xFF090C13});
        GradientDrawable teal = glow(c, 0x2E2DD4BF, 0.10f, 0.05f, 560);
        GradientDrawable indigo = glow(c, 0x2A6366F1, 0.92f, 1.05f, 640);
        return new LayerDrawable(new Drawable[] {base, teal, indigo});
    }

    private static GradientDrawable glow(Context c, int color, float x, float y, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        g.setGradientCenter(x, y);
        g.setGradientRadius(dp(c, radiusDp));
        return g;
    }

    /** One of QuietTV's line icons, tinted. */
    static Drawable icon(Context c, int res, int color) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    static GradientDrawable card(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(CARD);
        g.setStroke(Math.max(1, dp(c, 1)), CARD_EDGE);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A quiet, spaced-out section label like "YOUR APPS". */
    static TextView section(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.ROOT), 12, MUTED);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.16f);
        t.setPadding(dp(c, 2), dp(c, 14), 0, dp(c, 12));
        return t;
    }

    /** "QuietTV" with "TV" in the accent color. */
    static CharSequence wordmark() {
        SpannableString s = new SpannableString("QuietTV");
        s.setSpan(new TypefaceSpan("sans-serif-light"), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new TypefaceSpan("sans-serif-medium"), 5, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(ACCENT), 5, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }
}
