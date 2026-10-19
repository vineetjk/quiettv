package com.quiettv.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The home screen: a clock, your apps, your inputs and a few shortcuts. Nothing else. */
public class HomeActivity extends Activity {
    private static final int COLS = 6;
    private static final int PICK_APP = 1;
    static final String ADD = "shortcut:add", ALL = "shortcut:all", SETTINGS = "shortcut:settings",
            PLAY = "shortcut:play", STOCK = "shortcut:stock", NET = "shortcut:net";

    private LinearLayout column;
    private TextView clock, date, status;
    private boolean dirty = true, resumed;
    private String focusKey;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            dirty = true;
            if (resumed) rebuild();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        applyConfig(getIntent());

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF161D2A, 0xFF0B0E14});
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(bg);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(dp(48), dp(28), dp(48), dp(28));
        scroll.addView(column, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (applyConfig(i)) dirty = true;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.homeShown();
        Apps.prefs(this).edit().putBoolean("on_top", true).apply();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, tf);
        if (dirty) rebuild();
        updateClock();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        // Leaving for an app clears this. When Android closes QuietTV itself (an update, a crash), it stays
        // set, so the guard knows to bring QuietTV back when it restarts.
        if (!isFinishing()) Apps.prefs(this).edit().putBoolean("on_top", false).apply();
        unregisterReceiver(tick);
        View f = getCurrentFocus();
        if (f instanceof Tile) focusKey = ((Tile) f).key;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packages);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Out of sight: let go of the banner bitmaps. They're redrawn when Home is pressed again.
        if (level >= TRIM_MEMORY_UI_HIDDEN && !resumed && column != null) {
            column.removeAllViews();
            clock = date = status = null;
            dirty = true;
        }
    }

    /** Settings sent by the setup script: `am start ... --es apps "a,b,c" --es stamp <time>`. Applied once per stamp. */
    private boolean applyConfig(Intent i) {
        if (i == null || !i.hasExtra("stamp")) return false;
        SharedPreferences p = Apps.prefs(this);
        String stamp = i.getStringExtra("stamp");
        if (stamp == null || stamp.equals(p.getString("stamp", null))) return false;
        SharedPreferences.Editor e = p.edit().putString("stamp", stamp);
        String apps = i.getStringExtra("apps");
        if (apps != null) e.putString("apps", apps.replace("\"", "").replace(" ", ""));
        e.apply();
        return true;
    }

    // ---------------------------------------------------------------- layout

    private void rebuild() {
        dirty = false;
        if (getCurrentFocus() instanceof Tile) focusKey = ((Tile) getCurrentFocus()).key;
        column.removeAllViews();
        column.addView(header());

        int contentW = getResources().getDisplayMetrics().widthPixels - dp(96);
        int gap = dp(18);
        int w = (contentW - gap * (COLS - 1)) / COLS;
        int h = w * 9 / 16;

        column.addView(sectionTitle("Apps"));
        List<Tile> apps = new ArrayList<>();
        for (String pkg : Apps.homeList(this)) {
            Tile t = new Tile(this, pkg, w, h, Apps.banner(this, pkg, w, h), Apps.icon(this, pkg, dp(36)),
                    Apps.label(this, pkg), true);
            t.setOnClickListener(v -> launch(pkg));
            t.setOnLongClickListener(v -> { appMenu(pkg); return true; });
            apps.add(t);
        }
        Tile add = new Tile(this, ADD, w, h, null, null, "+  Add app", false);
        add.setOnClickListener(v -> pickApp());
        apps.add(add);
        addRows(apps, w, h, gap);

        column.addView(sectionTitle("Inputs & more"));
        int sh = h * 2 / 3;
        List<Tile> more = new ArrayList<>();
        for (TvInputInfo in : hdmiInputs()) {
            final String id = in.getId();
            Tile t = new Tile(this, "input:" + id, w, sh, null, null, inputLabel(in), false);
            t.setOnClickListener(v -> openInput(id));
            more.add(t);
        }
        more.add(shortcut(SETTINGS, "Settings", w, sh, v -> startSafely(new Intent(Settings.ACTION_SETTINGS))));
        if (Apps.installed(this, "com.android.vending"))
            more.add(shortcut(PLAY, "Play Store", w, sh, v -> launch("com.android.vending")));
        more.add(shortcut(ALL, "All apps", w, sh, v -> startActivity(new Intent(this, AppsActivity.class))));
        final ComponentName stock = Apps.stockHome(this);
        if (stock != null)
            more.add(shortcut(STOCK, "Original home", w, sh, v -> openStockHome(stock)));
        addRows(more, w, sh, gap);

        TextView hint = new TextView(this);
        hint.setText("Hold OK (or press Menu) on an app to move or remove it.");
        hint.setTextColor(0x66E8ECF4);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(20), 0, 0);
        column.addView(hint);

        updateClock();
        restoreFocus(apps);
    }

    private Tile shortcut(String key, String title, int w, int h, View.OnClickListener l) {
        Tile t = new Tile(this, key, w, h, null, null, title, false);
        t.setOnClickListener(l);
        return t;
    }

    private void addRows(List<Tile> tiles, int w, int h, int gap) {
        for (int i = 0; i < tiles.size(); i += COLS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            for (int j = i; j < Math.min(i + COLS, tiles.size()); j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (j > i) lp.leftMargin = gap;
                row.addView(tiles.get(j), lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(6);
            column.addView(row, rp);
        }
    }

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.TOP);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clock = new TextView(this);
        clock.setTextColor(0xFFF4F6FA);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        date = new TextView(this);
        date.setTextColor(0xB3E8ECF4);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        date.setPadding(dp(4), dp(4), 0, 0);
        left.addView(clock);
        left.addView(date);
        bar.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        status = new TextView(this);
        status.setTextColor(0xCCE8ECF4);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(dp(14), dp(8), dp(14), dp(8));
        status.setFocusable(true);
        final GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(18));
        chip.setColor(0x221C2230);
        status.setBackground(chip);
        status.setOnFocusChangeListener((v, has) -> {
            chip.setColor(has ? Tile.CARD_FOCUS : 0x221C2230);
            chip.setStroke(has ? dp(2) : 0, 0xFFFFFFFF);
        });
        status.setOnClickListener(v -> startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        bar.addView(status);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(18);
        bar.setLayoutParams(lp);
        return bar;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0x99E8ECF4);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(2), dp(14), 0, dp(10));
        return t;
    }

    private void restoreFocus(List<Tile> apps) {
        View target = null;
        if (focusKey != null) target = findTile(column, focusKey);
        if (target == null && !apps.isEmpty()) target = apps.get(0);
        if (target != null) target.requestFocus();
    }

    private static View findTile(ViewGroup g, String key) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof Tile && key.equals(((Tile) v).key)) return v;
            if (v instanceof ViewGroup && !(v instanceof Tile)) {
                View f = findTile((ViewGroup) v, key);
                if (f != null) return f;
            }
        }
        return null;
    }

    private void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        clock.setText(DateFormat.getTimeFormat(this).format(now));
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        date.setText(DateFormat.format(pattern, now));
        status.setText(network() + "   ·   " + freeMemory() + " free");
    }

    private String network() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        if (nc == null) return "Offline";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        return "Online";
    }

    private String freeMemory() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return (mi.availMem >> 20) + " MB";
    }

    // ---------------------------------------------------------------- actions

    private void launch(String pkg) {
        Intent i = Apps.launchIntent(getPackageManager(), pkg);
        if (i == null || !startSafely(i)) Toast.makeText(this, "Couldn't open " + Apps.label(this, pkg), Toast.LENGTH_SHORT).show();
    }

    private boolean startSafely(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Opens the TV's own home screen on purpose; Home brings you back here. */
    private void openStockHome(ComponentName stock) {
        Guard.allowStockHome();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(stock);
        if (!startSafely(i)) Toast.makeText(this, "Couldn't open the original home screen", Toast.LENGTH_SHORT).show();
    }

    private void pickApp() {
        startActivityForResult(new Intent(this, AppsActivity.class).putExtra(AppsActivity.PICK, true), PICK_APP);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK_APP || res != RESULT_OK || data == null) return;
        String pkg = data.getStringExtra(AppsActivity.PICK);
        List<String> list = Apps.homeList(this);
        if (pkg != null && !list.contains(pkg)) {
            list.add(pkg);
            Apps.saveHomeList(this, list);
        }
        focusKey = pkg;
        dirty = true;
    }

    private void appMenu(final String pkg) {
        final List<String> list = Apps.homeList(this);
        final int at = list.indexOf(pkg);
        final List<String> items = new ArrayList<>();
        if (at > 0) items.add("Move left");
        if (at >= 0 && at < list.size() - 1) items.add("Move right");
        items.add("Remove from home");
        items.add("App info");
        if (Apps.userInstalled(this, pkg)) items.add("Uninstall");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Apps.label(this, pkg))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String choice = items.get(which);
                    if (choice.startsWith("Move")) {
                        Collections.swap(list, at, choice.endsWith("left") ? at - 1 : at + 1);
                        Apps.saveHomeList(this, list);
                        focusKey = pkg;
                        rebuild();
                    } else if (choice.startsWith("Remove")) {
                        list.remove(pkg);
                        Apps.saveHomeList(this, list);
                        focusKey = null;
                        rebuild();
                    } else if (choice.equals("App info")) {
                        startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                    } else {
                        startSafely(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
                    }
                })
                .show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) return true; // this is home: Back has nowhere to go
        if (k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            View f = getCurrentFocus();
            if (f instanceof Tile && !((Tile) f).key.contains(":")) {
                appMenu(((Tile) f).key);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- HDMI inputs

    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return out;
        try {
            for (TvInputInfo in : tim.getTvInputList())
                if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null && !in.isHidden(this)) out.add(in);
        } catch (RuntimeException ignored) {
        }
        Collections.sort(out, (a, b) -> a.getId().compareTo(b.getId()));
        return out;
    }

    private String inputLabel(TvInputInfo in) {
        CharSequence custom = in.loadCustomLabel(this);
        if (custom != null && custom.length() > 0) return custom.toString();
        CharSequence l = in.loadLabel(this);
        return l != null && l.length() > 0 ? l.toString() : "HDMI";
    }

    private void openInput(String id) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        if (!startSafely(view)) Toast.makeText(this, "Couldn't open this input", Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
