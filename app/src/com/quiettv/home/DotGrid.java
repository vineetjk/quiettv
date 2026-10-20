package com.quiettv.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Dev mode's backdrop: QuietTV's usual background with a faint, evenly spaced dot grid on top. */
final class DotGrid extends Drawable {
    private final Drawable base;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float spacing, radius;

    DotGrid(Drawable base, float spacingPx, float radiusPx) {
        this.base = base;
        spacing = spacingPx;
        radius = radiusPx;
        dot.setColor(0x14FFFFFF);
    }

    @Override protected void onBoundsChange(Rect b) {
        base.setBounds(b);
    }

    @Override public void draw(Canvas c) {
        base.draw(c);
        Rect b = getBounds();
        for (float y = b.top + spacing / 2; y < b.bottom; y += spacing)
            for (float x = b.left + spacing / 2; x < b.right; x += spacing)
                c.drawCircle(x, y, radius, dot);
    }

    @Override public void setAlpha(int a) { dot.setAlpha(a); }

    @Override public void setColorFilter(ColorFilter f) { dot.setColorFilter(f); }

    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
