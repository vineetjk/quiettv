package com.quiettv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** QuietTV's own settings: Dev mode, the GitHub username and the graph colours. Remote-friendly. */
public class SettingsActivity extends Activity {
    static final String DEV = "dev", GITHUB = "github", PALETTE = "palette", UI_VERSION = "ui_version";
    private LinearLayout list;
    private int focusIndex;

    static boolean devOn(SharedPreferences p) {
        return p.getBoolean(DEV, false);
    }

    static int[] palette(SharedPreferences p) {
        return "teal".equals(p.getString(PALETTE, "green")) ? ContribGraph.TEAL : ContribGraph.GREEN;
    }

    /** Tells the home screen to rebuild itself next time it shows. */
    static void changed(SharedPreferences p) {
        p.edit().putInt(UI_VERSION, p.getInt(UI_VERSION, 0) + 1).apply();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Ui.background(this));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(56), dp(34), dp(56), dp(34));
        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        head.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView title = Ui.text(this, "QuietTV settings", 28, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(dp(14), 0, 0, 0);
        head.addView(title);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(660), -2);
        hp.bottomMargin = dp(22);
        col.addView(head, hp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, new LinearLayout.LayoutParams(dp(660), -2));
        setContentView(scroll);
        build();
    }

    private void build() {
        final SharedPreferences p = Apps.prefs(this);
        list.removeAllViews();
        boolean dev = devOn(p);
        row("Dev mode", "Show your GitHub contribution graph on the home screen. While it's on, QuietTV goes online to github.com only.",
                dev ? "On" : "Off", v -> {
                    p.edit().putBoolean(DEV, !devOn(p)).apply();
                    changed(p);
                    rebuildKeepingFocus(v);
                });
        if (dev) {
            String user = p.getString(GITHUB, "");
            row("GitHub username", "Whose public contributions to show. No account or password needed.",
                    user.isEmpty() ? "Not set" : "@" + user, this::askUsername);
            row("Graph colours", "Pick the look of the contribution graph.",
                    "teal".equals(p.getString(PALETTE, "green")) ? "QuietTV teal" : "GitHub green", v -> {
                        p.edit().putString(PALETTE, "teal".equals(p.getString(PALETTE, "green")) ? "green" : "teal").apply();
                        changed(p);
                        rebuildKeepingFocus(v);
                    });
            row("Refresh contributions now", "QuietTV refreshes by itself every 3 hours.", "", v -> refresh());
        }
        row("About QuietTV", "Free and open source: github.com/vineetjk/quiettv", version(), null);
        View target = focusIndex < list.getChildCount() ? list.getChildAt(focusIndex) : list.getChildAt(0);
        target.requestFocus();
    }

    private void rebuildKeepingFocus(View v) {
        focusIndex = list.indexOfChild(v);
        build();
    }

    private void askUsername(final View row) {
        final SharedPreferences p = Apps.prefs(this);
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setHint("your-github-name");
        field.setText(p.getString(GITHUB, ""));
        field.setSelection(field.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("GitHub username")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String u = field.getText().toString().trim().replaceFirst("^@", "");
                    if (!u.isEmpty() && !GitHub.validUser(u)) {
                        Toast.makeText(this, "That isn't a valid GitHub username.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    p.edit().putString(GITHUB, u).apply();
                    changed(p);
                    rebuildKeepingFocus(row);
                    if (!u.isEmpty()) refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refresh() {
        String user = Apps.prefs(this).getString(GITHUB, "");
        if (!GitHub.validUser(user)) {
            Toast.makeText(this, "Add your GitHub username first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Loading @" + user + "'s contributions…", Toast.LENGTH_SHORT).show();
        GitHub.refresh(this, user, cal -> {
            Toast.makeText(this, cal == null ? "Couldn't reach GitHub. Check the internet connection."
                    : String.format(java.util.Locale.getDefault(), "%,d contributions in the last year", cal.total), Toast.LENGTH_LONG).show();
            SettingsActivity.changed(Apps.prefs(this));
        });
    }

    // ------------------------------------------------------------------ one focusable row

    private void row(String title, String detail, String value, View.OnClickListener click) {
        final LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(12), dp(18), dp(12));
        r.setFocusable(true);
        final GradientDrawable bg = Ui.card(this, 14);
        r.setBackground(bg);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        final TextView t = Ui.text(this, title, 16, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        final TextView d = Ui.text(this, detail, 12, Ui.MUTED);
        d.setPadding(0, dp(4), 0, 0);
        d.setLineSpacing(0, 1.1f);
        text.addView(t);
        text.addView(d);
        r.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView v = Ui.text(this, value, 15, Ui.ACCENT);
        v.setPadding(dp(16), 0, 0, 0);
        r.addView(v);
        r.setOnFocusChangeListener((view, has) -> {
            bg.setColor(has ? Ui.FOCUS_BG : Ui.CARD);
            t.setTextColor(has ? Ui.FOCUS_FG : Ui.TEXT);
            d.setTextColor(has ? 0xFF4B5565 : Ui.MUTED);
            v.setTextColor(has ? 0xFF0F766E : Ui.ACCENT);
        });
        if (click != null) r.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        list.addView(r, lp);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
