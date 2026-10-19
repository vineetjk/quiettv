package com.quiettv.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One focusable tile. Three looks:
 *  BANNER   an app's TV banner; focus grows it a little, outlines it in white and shows its name below.
 *  APP      an app without a banner: its icon and name on a card.
 *  SHORTCUT a line icon over a label (inputs, Settings...); focus turns the card white with dark text.
 * Nothing animates and nothing moves except the focused tile itself.
 */
final class Tile extends LinearLayout {
    static final int BANNER = 0, APP = 1, SHORTCUT = 2, ADD = 3;

    final String key; // package name, "input:<id>" or a shortcut name
    private final int style;
    private final FrameLayout card;
    private final GradientDrawable bg;
    private final GradientDrawable ring;
    private final TextView caption;   // under BANNER tiles, shown on focus
    private TextView label;           // inside SHORTCUT / ADD / APP tiles
    private Drawable glyph;           // the line icon of SHORTCUT / ADD tiles

    Tile(Context c, String key, int w, int h, int style, Drawable image, String title) {
        super(c);
        this.key = key;
        this.style = style;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        int r = Ui.dp(c, style == BANNER ? 12 : 14);

        card = new FrameLayout(c);
        bg = Ui.card(c, style == BANNER ? 12 : 14);
        if (style == ADD) bg.setStroke(Ui.dp(c, 1.5f), 0x4DFFFFFF, Ui.dp(c, 6), Ui.dp(c, 5));
        if (style == BANNER) bg.setColor(0xFF1B2230);
        card.setBackground(bg);
        card.setClipToOutline(true);
        ring = new GradientDrawable();
        ring.setCornerRadius(r);
        ring.setColor(Color.TRANSPARENT);
        card.setForeground(ring);
        addView(card, new LayoutParams(w, h));

        if (style == BANNER) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(image);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout inner = new LinearLayout(c);
            inner.setOrientation(VERTICAL);
            inner.setGravity(Gravity.CENTER);
            if (image != null) {
                ImageView iv = new ImageView(c);
                int s = style == APP ? Math.min(h / 2, Ui.dp(c, 36)) : Ui.dp(c, 22);
                if (style != APP) {
                    glyph = image;
                    glyph.setTint(Ui.TEXT);
                }
                iv.setImageDrawable(image);
                inner.addView(iv, new LayoutParams(s, s));
            }
            label = Ui.text(c, title, 13, Ui.TEXT);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(Ui.dp(c, 8), Ui.dp(c, image != null ? 8 : 0), Ui.dp(c, 8), 0);
            if (style == ADD) label.setTextColor(Ui.MUTED);
            inner.addView(label, new LayoutParams(-1, -2));
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }

        caption = Ui.text(c, title, 13, Ui.TEXT);
        caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0, Ui.dp(c, 10), 0, 0);
        caption.setVisibility(style == BANNER ? INVISIBLE : GONE);
        addView(caption, new LayoutParams(w, -2));

        setOnFocusChangeListener((v, has) -> showFocus(has));
    }

    private void showFocus(boolean has) {
        Context c = getContext();
        float s = has ? (style == BANNER ? 1.08f : 1.05f) : 1f;
        card.setScaleX(s);
        card.setScaleY(s);
        if (style == BANNER) {
            ring.setStroke(has ? Ui.dp(c, 3) : 0, Color.WHITE);
            caption.setVisibility(has ? VISIBLE : INVISIBLE);
            return;
        }
        // Cards turn white when focused, with dark text and icon, like Google TV's own chips.
        bg.setColor(has ? Ui.FOCUS_BG : Ui.CARD);
        if (style == ADD) {
            if (has) bg.setStroke(0, 0);
            else bg.setStroke(Ui.dp(c, 1.5f), 0x4DFFFFFF, Ui.dp(c, 6), Ui.dp(c, 5));
        } else {
            bg.setStroke(has ? 0 : Math.max(1, Ui.dp(c, 1)), Ui.CARD_EDGE);
        }
        if (label != null) label.setTextColor(has ? Ui.FOCUS_FG : (style == ADD ? Ui.MUTED : Ui.TEXT));
        if (glyph != null) glyph.setTint(has ? Ui.FOCUS_FG : Ui.TEXT);
    }

    /** A tile-sized gap that can't be focused. */
    static View spacer(Context c, int w, int h) {
        View v = new View(c);
        v.setLayoutParams(new LayoutParams(w, h));
        v.setFocusable(false);
        return v;
    }
}
