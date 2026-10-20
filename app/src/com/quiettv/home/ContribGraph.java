package com.quiettv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A GitHub-style contribution graph: one column per week, one row per weekday (Sunday on top),
 * month names above and Mon / Wed / Fri on the left. Drawn directly; nothing animates.
 */
final class ContribGraph extends View {
    /** Five colours per palette, from "no contributions" to "most". */
    static final int[] GREEN = {0x14FFFFFF, 0xFF0E4429, 0xFF006D32, 0xFF26A641, 0xFF39D353};
    static final int[] TEAL = {0x14FFFFFF, 0xFF134E4A, 0xFF0F766E, 0xFF14B8A6, 0xFF5EEAD4};

    private final Paint cell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private List<GitHub.Day> days;
    private int[] palette = GREEN;
    private int firstWeekday; // weekday of the first day, 0 = Sunday
    private float size, gap, labelW, monthH, radius;

    ContribGraph(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFF2F5FA);
        label.setColor(Ui.MUTED);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    void set(List<GitHub.Day> days, int[] palette, float cellPx) {
        this.days = days;
        this.palette = palette;
        size = cellPx;
        gap = Math.max(2, Math.round(cellPx * 0.22f));
        radius = Math.max(1.5f, cellPx * 0.22f);
        float density = getResources().getDisplayMetrics().density;
        label.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        labelW = 28 * density;
        monthH = 16 * density;
        ring.setStrokeWidth(Math.max(1.5f, density * 1.5f));
        firstWeekday = days.isEmpty() ? 0 : weekday(days.get(0).date);
        requestLayout();
        invalidate();
    }

    /** The cell size that fits this many pixels of width for a year (54 week columns at most). */
    static float cellFor(Context c, int widthPx) {
        float labelW = 28 * c.getResources().getDisplayMetrics().density;
        float step = (widthPx - labelW) / 54f;
        return Math.max(4, step / 1.22f);
    }

    private int columns() {
        return days == null || days.isEmpty() ? 53 : (firstWeekday + days.size() + 6) / 7;
    }

    @Override protected void onMeasure(int w, int h) {
        int width = Math.round(labelW + columns() * (size + gap));
        int height = Math.round(monthH + 7 * (size + gap));
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (days == null || days.isEmpty()) return;
        float step = size + gap;
        // Weekday names on rows 1, 3 and 5 (Mon, Wed, Fri), like github.com.
        String[] names = {"Mon", "Wed", "Fri"};
        for (int i = 0; i < 3; i++) {
            float y = monthH + (1 + 2 * i) * step + size * 0.82f;
            canvas.drawText(names[i], 0, y, label);
        }
        String lastMonth = "";
        float lastLabelX = -1000;
        for (int i = 0; i < days.size(); i++) {
            GitHub.Day d = days.get(i);
            int index = firstWeekday + i;
            int col = index / 7, row = index % 7;
            float x = labelW + col * step, y = monthH + row * step;
            // A month's name over the first week that starts in it, if there's room.
            String month = d.date.substring(5, 7);
            if (row == 0 || i == 0) {
                if (!month.equals(lastMonth) && x - lastLabelX > step * 3) {
                    canvas.drawText(monthName(month), x, monthH * 0.72f, label);
                    lastLabelX = x;
                }
                lastMonth = month;
            }
            cell.setColor(palette[Math.max(0, Math.min(4, d.level))]);
            r.set(x, y, x + size, y + size);
            canvas.drawRoundRect(r, radius, radius, cell);
            if (i == days.size() - 1) {
                float o = ring.getStrokeWidth();
                r.set(x - o, y - o, x + size + o, y + size + o);
                canvas.drawRoundRect(r, radius + o, radius + o, ring);
            }
        }
    }

    private static String monthName(String mm) {
        String[] n = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        int m = Integer.parseInt(mm);
        return m >= 1 && m <= 12 ? n[m - 1] : "";
    }

    private static int weekday(String date) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(date);
            java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.setTime(d);
            return c.get(java.util.Calendar.DAY_OF_WEEK) - 1; // Sunday = 0
        } catch (ParseException e) {
            return 0;
        }
    }
}
