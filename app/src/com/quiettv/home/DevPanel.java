package com.quiettv.home;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dev mode's card: the GitHub contribution graph on the left, the year's total and streaks on the right.
 * Not focusable, so it never takes focus from the app tiles below it.
 */
final class DevPanel extends LinearLayout {
    private final int width;

    DevPanel(Context c, int widthPx) {
        super(c);
        width = widthPx;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(false);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x12FFFFFF, 0x08FFFFFF});
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1)), Ui.CARD_EDGE);
        setBackground(bg);
        setPadding(dp(20), dp(12), dp(20), dp(12));
    }

    /** Shows the calendar, or a message when there's nothing to show yet. */
    void show(String user, GitHub.Calendar cal, int[] palette, String problem) {
        removeAllViews();
        if (cal == null) {
            TextView t = Ui.text(getContext(), message(user, problem), 15, Ui.MUTED);
            t.setLineSpacing(0, 1.2f);
            t.setPadding(0, dp(18), 0, dp(18));
            addView(t, new LayoutParams(-1, -2));
            return;
        }
        int statsW = dp(196);
        int graphW = width - getPaddingLeft() - getPaddingRight() - statsW - dp(24);

        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(VERTICAL);
        LinearLayout title = new LinearLayout(getContext());
        title.setGravity(Gravity.CENTER_VERTICAL);
        ImageView code = new ImageView(getContext());
        code.setImageDrawable(Ui.icon(getContext(), R.drawable.ic_code, palette[4]));
        title.addView(code, new LayoutParams(dp(16), dp(16)));
        TextView who = Ui.text(getContext(), "@" + cal.user, 14, Ui.TEXT);
        who.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        who.setPadding(dp(8), 0, 0, 0);
        title.addView(who);
        left.addView(title);
        ContribGraph graph = new ContribGraph(getContext());
        graph.set(cal.days, palette, ContribGraph.cellFor(getContext(), graphW));
        LayoutParams gp = new LayoutParams(-2, -2);
        gp.topMargin = dp(6);
        left.addView(graph, gp);
        // The colour key sits under the graph, on the right, like on github.com.
        LinearLayout key = new LinearLayout(getContext());
        key.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        key.addView(legend(palette));
        LayoutParams kp = new LayoutParams(-1, -2);
        kp.topMargin = dp(4);
        left.addView(key, kp);
        addView(left, new LayoutParams(graphW, -2));

        View divider = new View(getContext());
        divider.setBackgroundColor(Ui.CARD_EDGE);
        LayoutParams dlp = new LayoutParams(Math.max(1, dp(1)), -1);
        dlp.leftMargin = dlp.rightMargin = dp(12);
        addView(divider, dlp);

        LinearLayout stats = new LinearLayout(getContext());
        stats.setOrientation(VERTICAL);
        TextView total = Ui.text(getContext(), String.format(java.util.Locale.getDefault(), "%,d", cal.total), 30, Ui.TEXT);
        total.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        stats.addView(total);
        TextView totalLabel = Ui.text(getContext(), "contributions in the last year", 12, Ui.MUTED);
        totalLabel.setPadding(0, dp(2), 0, dp(8));
        stats.addView(totalLabel);
        stats.addView(streakPill(cal.current, palette));
        String today = cal.today < 0 ? "active" : String.valueOf(cal.today);
        TextView more = Ui.text(getContext(), "Longest " + days(cal.longest) + "  ·  Today " + today, 12, Ui.MUTED);
        more.setPadding(0, dp(8), 0, 0);
        stats.addView(more);
        TextView when = Ui.text(getContext(), updated(cal.fetchedAt, problem), 11, Ui.FAINT);
        when.setPadding(0, dp(4), 0, 0);
        stats.addView(when);
        addView(stats, new LayoutParams(statsW, -2));
    }

    private View streakPill(int streak, int[] palette) {
        LinearLayout pill = new LinearLayout(getContext());
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(10), dp(5), dp(12), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor((palette[4] & 0x00FFFFFF) | 0x26000000);
        pill.setBackground(bg);
        ImageView flame = new ImageView(getContext());
        flame.setImageDrawable(Ui.icon(getContext(), R.drawable.ic_flame, streak > 0 ? 0xFFFB923C : Ui.FAINT));
        pill.addView(flame, new LayoutParams(dp(15), dp(15)));
        TextView t = Ui.text(getContext(), streak > 0 ? days(streak).replace(" days", "-day").replace(" day", "-day") + " streak" : "No streak yet", 13, palette[4]);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(6), 0, 0, 0);
        pill.addView(t);
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.addView(pill);
        return wrap;
    }

    private View legend(int[] palette) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(getContext(), "Less", 10, Ui.FAINT));
        for (int c : palette) {
            View sq = new View(getContext());
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(2));
            g.setColor(c);
            sq.setBackground(g);
            LayoutParams lp = new LayoutParams(dp(9), dp(9));
            lp.leftMargin = dp(3);
            row.addView(sq, lp);
        }
        TextView more = Ui.text(getContext(), "More", 10, Ui.FAINT);
        more.setPadding(dp(4), 0, 0, 0);
        row.addView(more);
        return row;
    }

    private static String days(int n) {
        return n == 1 ? "1 day" : n + " days";
    }

    private static String updated(long at, String problem) {
        long min = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        String ago = min < 1 ? "just now" : min < 60 ? min + " min ago" : min < 48 * 60 ? (min / 60) + " h ago" : (min / 1440) + " days ago";
        return problem != null ? "Offline · from " + ago : "Updated " + ago;
    }

    private static String message(String user, String problem) {
        if (!GitHub.validUser(user))
            return "Dev mode is on. Add your GitHub username in QuietTV settings (the ✦ QuietTV tile below) to see your contribution graph here.";
        if (problem != null) return "Couldn't load @" + user + "'s contributions: " + problem + ". QuietTV will try again later.";
        return "Loading @" + user + "'s contributions…";
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}
