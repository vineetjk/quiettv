package com.quiettv.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
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
        scroll.setBackground(Ui.background(this));
        scroll.setClipChildren(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(56), dp(34), dp(56), dp(32));
        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView glyph = new ImageView(this);
        glyph.setImageDrawable(Ui.icon(this, pick ? R.drawable.ic_add : R.drawable.ic_apps, Ui.ACCENT));
        head.addView(glyph, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView title = Ui.text(this, pick ? "Choose an app to add" : "All apps", 30, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(dp(14), 0, 0, 0);
        head.addView(title);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = dp(26);
        col.addView(head, hp);

        int gap = dp(18);
        int w = (getResources().getDisplayMetrics().widthPixels - dp(112) - gap * (COLS - 1)) / COLS;
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
                rp.bottomMargin = dp(4);
                col.addView(row, rp);
            }
            Drawable banner = Apps.banner(this, pkg, w, h);
            Tile t = banner != null ? new Tile(this, pkg, w, h, Tile.BANNER, banner, Apps.label(this, pkg))
                    : new Tile(this, pkg, w, h, Tile.APP, Apps.icon(this, pkg, dp(36)), Apps.label(this, pkg));
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
            TextView none = Ui.text(this, "Every app is already on your home screen.", 16, Ui.MUTED);
            col.addView(none);
        }
        setContentView(scroll);
        if (first != null) first.requestFocus();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
