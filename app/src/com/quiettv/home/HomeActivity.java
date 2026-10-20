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
import android.graphics.drawable.Drawable;
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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
            PLAY = "shortcut:play", STOCK = "shortcut:stock", NET = "shortcut:net", QUIET = "shortcut:quiettv";

    private LinearLayout column;
    private ScrollView scroll;
    private DevPanel devPanel;
    private String devProblem;
    private int uiVersion = -1;
    private TextView clock, date, netText, memText;
    private ImageView netIcon, memIcon;
    private boolean chipFocused;
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

        scroll = new ScrollView(this);
        scroll.setBackground(Ui.background(this));
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(dp(56), dp(30), dp(56), dp(24));
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
        int v = Apps.prefs(this).getInt(SettingsActivity.UI_VERSION, 0);
        if (v != uiVersion) {
            uiVersion = v;
            dirty = true;
        }
        if (dirty) rebuild();
        updateClock();
        maybeFetchContributions();
    }

    /** Dev mode only: refresh the GitHub graph if it's more than a few hours old. */
    private void maybeFetchContributions() {
        SharedPreferences p = Apps.prefs(this);
        if (!SettingsActivity.devOn(p)) return;
        String user = p.getString(SettingsActivity.GITHUB, "");
        if (!GitHub.due(this, user)) return;
        GitHub.refresh(this, user, cal -> {
            devProblem = cal == null ? "GitHub couldn't be reached" : null;
            if (devPanel != null) showDev();
        });
    }

    private void showDev() {
        SharedPreferences p = Apps.prefs(this);
        String user = p.getString(SettingsActivity.GITHUB, "");
        devPanel.show(user, GitHub.cached(this, user), SettingsActivity.palette(p), devProblem);
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
            clock = date = netText = memText = null;
            netIcon = memIcon = null;
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
        // Dev mode from `quiettv.sh dev`: --ez dev true|false, --es github <name>, --es palette green|teal
        if (i.hasExtra("dev")) e.putBoolean(SettingsActivity.DEV, i.getBooleanExtra("dev", false));
        String gh = i.getStringExtra("github");
        if (gh != null && (gh.isEmpty() || GitHub.validUser(gh))) e.putString(SettingsActivity.GITHUB, gh);
        String pal = i.getStringExtra("palette");
        if ("green".equals(pal) || "teal".equals(pal)) e.putString(SettingsActivity.PALETTE, pal);
        e.apply();
        return true;
    }

    // ---------------------------------------------------------------- layout

    private void rebuild() {
        dirty = false;
        if (getCurrentFocus() instanceof Tile) focusKey = ((Tile) getCurrentFocus()).key;
        column.removeAllViews();
        final boolean dev = SettingsActivity.devOn(Apps.prefs(this));
        column.setPadding(dp(56), dp(dev ? 20 : 30), dp(56), dp(dev ? 16 : 24));
        scroll.setBackground(dev ? new DotGrid(Ui.background(this), dp(26), 1.2f * getResources().getDisplayMetrics().density) : Ui.background(this));
        column.addView(header(dev));

        int contentW = getResources().getDisplayMetrics().widthPixels - dp(112);
        int gap = dp(18);
        int w = (contentW - gap * (COLS - 1)) / COLS;
        int h = w * 9 / 16;

        // Dev mode: the GitHub graph sits in its own band above the apps, so tiles never cover it.
        if (dev) {
            devPanel = new DevPanel(this, contentW);
            LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(-1, -2);
            devLp.topMargin = dp(6);
            column.addView(devPanel, devLp);
            showDev();
        } else {
            devPanel = null;
        }

        // "YOUR APPS" on the left, the how-to hint on the right of the same line.
        LinearLayout appsHead = new LinearLayout(this);
        appsHead.setGravity(Gravity.BOTTOM);
        appsHead.addView(section("Your apps", dev), new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = Ui.text(this, "Hold OK on an app to move or remove it", 12, Ui.FAINT);
        hint.setPadding(0, 0, dp(2), dp(dev ? 8 : 12));
        appsHead.addView(hint);
        column.addView(appsHead);
        List<Tile> apps = new ArrayList<>();
        for (String pkg : Apps.homeList(this)) {
            Drawable banner = Apps.banner(this, pkg, w, h);
            Tile t = banner != null ? new Tile(this, pkg, w, h, Tile.BANNER, banner, Apps.label(this, pkg))
                    : new Tile(this, pkg, w, h, Tile.APP, Apps.icon(this, pkg, dp(36)), Apps.label(this, pkg));
            t.setOnClickListener(v -> launch(pkg));
            t.setOnLongClickListener(v -> { appMenu(pkg); return true; });
            apps.add(t);
        }
        Tile add = new Tile(this, ADD, w, h, Tile.ADD, Ui.icon(this, R.drawable.ic_add, Ui.MUTED), "Add app");
        add.setOnClickListener(v -> pickApp());
        apps.add(add);
        // Dev mode keeps the apps to one row that scrolls sideways, so the screen never overflows.
        if (dev) addScrollRow(apps, gap);
        else addRows(apps, w, h, gap);

        column.addView(section("Inputs & more", dev));
        List<TvInputInfo> inputs = hdmiInputs();
        final ComponentName stock = Apps.stockHome(this);
        boolean play = Apps.installed(this, "com.android.vending");
        int n = inputs.size() + 3 + (play ? 1 : 0) + (stock != null ? 1 : 0);
        // Shortcuts share one row: narrower tiles when there's room for all of them, scrolling when not.
        int sw = Math.max(dp(104), Math.min(w, (contentW - gap * (n - 1)) / n));
        int sh = dev ? dp(58) : Math.max(h * 2 / 3, dp(66));
        List<Tile> more = new ArrayList<>();
        for (TvInputInfo in : inputs) {
            final String id = in.getId();
            Tile t = new Tile(this, "input:" + id, sw, sh, Tile.SHORTCUT, Ui.icon(this, R.drawable.ic_hdmi, Ui.TEXT), inputLabel(in));
            t.setOnClickListener(v -> openInput(id));
            more.add(t);
        }
        more.add(shortcut(SETTINGS, R.drawable.ic_settings, "Settings", sw, sh, v -> startSafely(new Intent(Settings.ACTION_SETTINGS))));
        if (play) more.add(shortcut(PLAY, R.drawable.ic_play, "Play Store", sw, sh, v -> launch("com.android.vending")));
        more.add(shortcut(ALL, R.drawable.ic_apps, "All apps", sw, sh, v -> startActivity(new Intent(this, AppsActivity.class))));
        if (stock != null) more.add(shortcut(STOCK, R.drawable.ic_home, "Original home", sw, sh, v -> openStockHome(stock)));
        more.add(shortcut(QUIET, R.drawable.ic_sparkle, "QuietTV", sw, sh, v -> startActivity(new Intent(this, SettingsActivity.class))));
        addScrollRow(more, gap);

        updateClock();
        restoreFocus(apps);
    }

    private TextView section(String s, boolean dev) {
        TextView t = Ui.section(this, s);
        if (dev) t.setPadding(dp(2), dp(10), 0, dp(8));
        return t;
    }

    /** One row of tiles that scrolls sideways when it doesn't fit, with a soft fade at the edges. */
    private void addScrollRow(List<Tile> tiles, int gap) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setHorizontalFadingEdgeEnabled(true);
        hs.setFadingEdgeLength(dp(36));
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(4), 0, dp(4));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        for (int j = 0; j < tiles.size(); j++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (j > 0) lp.leftMargin = gap;
            row.addView(tiles.get(j), lp);
        }
        hs.addView(row);
        column.addView(hs, new LinearLayout.LayoutParams(-1, -2));
    }

    private Tile shortcut(String key, int icon, String title, int w, int h, View.OnClickListener l) {
        Tile t = new Tile(this, key, w, h, Tile.SHORTCUT, Ui.icon(this, icon, Ui.TEXT), title);
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
            rp.bottomMargin = dp(4);
            column.addView(row, rp);
        }
    }

    /** The clock on the left; the QuietTV logo and a network/memory chip on the right. */
    private View header(boolean dev) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.TOP);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, "", dev ? 48 : 66, Ui.TEXT);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        date = Ui.text(this, "", 18, Ui.MUTED);
        date.setPadding(dp(4), dp(dev ? 6 : 10), 0, 0);
        left.addView(clock);
        left.addView(date);
        bar.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView word = Ui.text(this, Ui.wordmark(), 21, Ui.TEXT);
        word.setPadding(dp(10), 0, dp(2), 0);
        brand.addView(word);
        right.addView(brand);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(16);
        right.addView(statusChip(), cp);
        bar.addView(right);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        bar.setLayoutParams(lp);
        return bar;
    }

    /** Network and free memory. Focusable: OK opens network settings. */
    private View statusChip() {
        final LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(14), dp(8), dp(16), dp(8));
        chip.setFocusable(true);
        final GradientDrawable bg = Ui.card(this, 20);
        chip.setBackground(bg);
        netIcon = new ImageView(this);
        chip.addView(netIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        netText = Ui.text(this, "", 14, Ui.TEXT);
        netText.setPadding(dp(8), 0, dp(16), 0);
        chip.addView(netText);
        memIcon = new ImageView(this);
        chip.addView(memIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        memText = Ui.text(this, "", 14, Ui.MUTED);
        memText.setPadding(dp(8), 0, 0, 0);
        chip.addView(memText);
        chip.setOnFocusChangeListener((v, has) -> {
            chipFocused = has;
            bg.setColor(has ? Ui.FOCUS_BG : Ui.CARD);
            bg.setStroke(has ? 0 : Math.max(1, dp(1)), Ui.CARD_EDGE);
            updateClock();
        });
        chip.setOnClickListener(v -> startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        return chip;
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
        if (netText == null) return;
        int fg = chipFocused ? Ui.FOCUS_FG : Ui.TEXT, fg2 = chipFocused ? Ui.FOCUS_FG : Ui.MUTED;
        String net = network();
        int res = net.equals("Offline") ? R.drawable.ic_offline
                : net.equals("Ethernet") ? R.drawable.ic_ethernet : R.drawable.ic_wifi;
        netIcon.setImageDrawable(Ui.icon(this, res, fg));
        netText.setText(net);
        netText.setTextColor(fg);
        memIcon.setImageDrawable(Ui.icon(this, R.drawable.ic_memory, fg2));
        memText.setText(freeMemory() + " free");
        memText.setTextColor(fg2);
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
