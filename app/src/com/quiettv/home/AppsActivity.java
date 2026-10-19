package com.quiettv.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Every app on the TV in a grid. With PICK set, choosing one returns it instead of opening it. */
public class AppsActivity extends Activity {
    static final String PICK = "pick";
    private static final int COLS = 6;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final boolean pick = getIntent().getBooleanExtra(PICK, false);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF161D2A, 0xFF0B0E14}));
        scroll.setClipChildren(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(48), dp(32), dp(48), dp(32));
        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText(pick ? "Choose an app to add" : "All apps");
        title.setTextColor(0xFFF4F6FA);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(0, 0, 0, dp(20));
        col.addView(title);

        int gap = dp(18);
        int w = (getResources().getDisplayMetrics().widthPixels - dp(96) - gap * (COLS - 1)) / COLS;
        int h = w * 9 / 16;
        List<String> pkgs = Apps.launchable(this);
        if (pick) pkgs.removeAll(Apps.homeList(this));

        LinearLayout row = null;
        Tile first = null;
        for (int i = 0; i < pkgs.size(); i++) {
            final String pkg = pkgs.get(i);
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = dp(6);
                col.addView(row, rp);
            }
            Tile t = new Tile(this, pkg, w, h, Apps.banner(this, pkg, w, h), Apps.icon(this, pkg, dp(36)),
                    Apps.label(this, pkg), true);
            t.setOnClickListener(v -> {
                if (pick) {
                    setResult(RESULT_OK, new Intent().putExtra(PICK, pkg));
                    finish();
                } else {
                    Intent li = Apps.launchIntent(getPackageManager(), pkg);
                    try {
                        startActivity(li);
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "Couldn't open " + Apps.label(this, pkg), Toast.LENGTH_SHORT).show();
                    }
                }
            });
            t.setOnLongClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                } catch (RuntimeException ignored) {
                }
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i % COLS != 0) lp.leftMargin = gap;
            row.addView(t, lp);
            if (first == null) first = t;
        }
        if (pkgs.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Every app is already on your home screen.");
            none.setTextColor(0xB3E8ECF4);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            col.addView(none);
        }
        setContentView(scroll);
        if (first != null) first.requestFocus();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
