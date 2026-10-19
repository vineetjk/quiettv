package com.quiettv.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One focusable tile: an app banner, or an icon with a title for inputs and shortcuts.
 * Focus makes it a little bigger with a white outline; nothing else moves.
 */
final class Tile extends LinearLayout {
    static final int CARD = 0xFF1C2230;
    static final int CARD_FOCUS = 0xFF2A3346;

    final String key;          // package name, input id, or a shortcut name
    private final FrameLayout card;
    private final TextView caption;
    private final GradientDrawable bg;
    private final GradientDrawable ring;

    Tile(Context c, String key, int w, int h, Drawable banner, Drawable icon, String title, boolean showCaption) {
        super(c);
        this.key = key;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        int r = dp(10);

        card = new FrameLayout(c);
        bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(CARD);
        card.setBackground(bg);
        card.setClipToOutline(true);
        ring = new GradientDrawable();
        ring.setCornerRadius(r);
        ring.setColor(Color.TRANSPARENT);
        card.setForeground(ring);
        addView(card, new LayoutParams(w, h));

        if (banner != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(banner);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout inner = new LinearLayout(c);
            inner.setOrientation(VERTICAL);
            inner.setGravity(Gravity.CENTER);
            if (icon != null) {
                ImageView iv = new ImageView(c);
                iv.setImageDrawable(icon);
                int s = Math.min(h / 2, dp(36));
                inner.addView(iv, new LayoutParams(s, s));
            }
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextColor(0xFFE8ECF4);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(8), dp(6), dp(8), 0);
            inner.addView(t, new LayoutParams(-1, -2));
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }

        caption = new TextView(c);
        caption.setText(title);
        caption.setTextColor(0xFFE8ECF4);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0, dp(8), 0, 0);
        caption.setVisibility(showCaption ? INVISIBLE : GONE);
        addView(caption, new LayoutParams(w, -2));

        setOnFocusChangeListener((v, has) -> showFocus(has, showCaption));
    }

    private void showFocus(boolean has, boolean showCaption) {
        float s = has ? 1.08f : 1f;
        card.setScaleX(s);
        card.setScaleY(s);
        card.setTranslationZ(has ? dp(8) : 0);
        ring.setStroke(has ? dp(3) : 0, Color.WHITE);
        bg.setColor(has ? CARD_FOCUS : CARD);
        if (showCaption) caption.setVisibility(has ? VISIBLE : INVISIBLE);
        if (has) caption.setTranslationY(dp(4));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** A tile that keeps its place but can't be focused, used to pad rows. */
    static View spacer(Context c, int w, int h) {
        View v = new View(c);
        v.setLayoutParams(new LayoutParams(w, h));
        v.setFocusable(false);
        return v;
    }
}
