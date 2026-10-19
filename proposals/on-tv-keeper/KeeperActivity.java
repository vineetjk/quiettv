package com.quiettv.home;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Turns on "keep my changes": the user confirms here, then the TV asks "Allow debugging?" for QuietTV.
 * Nothing connects until the button is pressed, so another app opening this screen can't do anything.
 */
public class KeeperActivity extends Activity {
    private TextView status;
    private Button go;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(dp(96), dp(48), dp(96), dp(48));
        col.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFF161D2A, 0xFF0B0E14}));

        TextView title = text("Keep your changes after a restart", 30, 0xFFF4F6FA);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        col.addView(title);
        col.addView(text("Some TVs turn apps back on every time they restart. QuietTV can put your changes back by "
                + "itself, using the TV's own debugging connection, the same way your computer does.\n\n"
                + "It only re-applies the list of changes you made with quiettv.sh (apps turned off, settings, "
                + "the Home button). Network debugging must stay on.\n\n"
                + "Press the button, then on the \"Allow debugging?\" message tick \"Always allow\" and press Allow.",
                17, 0xCCE8ECF4));

        go = new Button(this);
        go.setText(Keeper.enabled(this) ? "Check now" : "Allow QuietTV to connect");
        go.setAllCaps(false);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(24);
        col.addView(go, lp);
        status = text(Keeper.enabled(this) ? Keeper.lastResult(this) : "", 16, 0xFFB8F5C8);
        col.addView(status);

        go.setOnClickListener(v -> {
            go.setEnabled(false);
            status.setText("Connecting... look for \"Allow debugging?\" on the screen.");
            new Thread(() -> {
                final String r = Keeper.pair(this);
                runOnUiThread(() -> {
                    status.setText(r);
                    go.setEnabled(true);
                    go.setText(Keeper.enabled(this) ? "Check now" : "Try again");
                });
            }).start();
        });
        setContentView(col);
        go.requestFocus();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, dp(12), 0, 0);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
