package com.quiettv.setup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QuietTV Setup: sets up a TV from a phone, step by step, asking before every change.
 * One activity, one page at a time; anything that talks to the TV runs off the main thread.
 */
public class SetupActivity extends Activity {
    private static final int BG = 0xFF0B0F17, CARD = 0xFF161D2A, EDGE = 0xFF232C3D, TEXT = 0xFFF2F5FA,
            MUTED = 0xFFA6B0C0, ACCENT = 0xFF2DD4BF, ON_ACCENT = 0xFF042F2E, WARN = 0xFFFBBF24;
    private static final String ISSUES = "https://github.com/" + Release.REPO + "/issues/new?template=1-setup-help.yml";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Deque<Runnable> history = new ArrayDeque<>();
    private Runnable current;
    private LinearLayout column;
    private ScrollView scroll;

    private Tv tv;
    private String ip;
    private int kind = 0;
    private int freeBefore = -1;

    interface Task { void run() throws Exception; }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFF111827, BG}));
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(24), dp(22), dp(32));
        scroll.addView(column, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);
        go(this::welcome);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        if (tv != null) tv.adb.close();
    }

    @Override public void onBackPressed() {
        if (history.isEmpty()) super.onBackPressed();
        else {
            current = history.pop();
            current.run();
        }
    }

    /** Shows a page and remembers the one before it for Back. */
    private void go(Runnable page) {
        if (current != null) history.push(current);
        current = page;
        page.run();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("setup", MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ pages

    private void welcome() {
        page(null);
        title("Make your TV calm and fast");
        body("QuietTV Setup connects to your Android TV or Google TV over Wi-Fi. With your OK at every step, it "
                + "turns off junk apps, makes menus snappier and installs the QuietTV home screen.");
        card(bullets("What you need:", new String[] {
            "Your TV and this phone on the same Wi-Fi",
            "The TV remote",
            "About 5 minutes",
        }));
        body("Nothing is deleted, and you can undo everything later from this app.");
        final String saved = prefs().getString("ip", null);
        if (saved != null) primary("Continue with my TV (" + saved + ")", () -> connect(saved));
        if (saved == null) primary("Start", () -> go(this::whichTv));
        else secondary("Set up a different TV", () -> go(this::whichTv));
    }

    private void whichTv() {
        page("Step 1 of 6");
        title("Which TV do you have?");
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < Guide.KINDS.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(i + 1);
            r.setText(Guide.KINDS[i]);
            r.setTextColor(TEXT);
            r.setTextSize(17);
            r.setButtonTintList(ColorStateList.valueOf(ACCENT));
            r.setPadding(dp(6), dp(10), 0, 0);
            g.addView(r);
            TextView h = text(Guide.KIND_HINTS[i], 13, MUTED);
            h.setPadding(dp(38), 0, 0, dp(8));
            g.addView(h);
        }
        g.check(kind + 1);
        g.setOnCheckedChangeListener((group, id) -> kind = id - 1);
        card(g);
        primary("Next", () -> go(this::developerMode));
    }

    private void developerMode() {
        page("Step 2 of 6");
        title("Turn on developer mode");
        body("Use the TV remote for this. It's a one-time thing.");
        card(numbered(Guide.developerSteps(kind)));
        note(Guide.PAIRING_NOTE);
        primary("Done, find my TV", () -> go(this::findTv));
    }

    private void findTv() {
        page("Step 3 of 6");
        title("Find your TV");
        body("Make sure this phone is on the same Wi-Fi as the TV.");
        primary("Search my Wi-Fi", () -> work("Looking for TVs on your Wi-Fi...", () -> {
            final List<String> found = Finder.scan(this);
            runOnUiThread(() -> go(() -> found(found)));
        }));
        LinearLayout manual = new LinearLayout(this);
        manual.setOrientation(LinearLayout.VERTICAL);
        manual.addView(text("Or type the TV's IP address", 15, TEXT));
        final EditText field = new EditText(this);
        field.setHint("192.168.1.50");
        field.setHintTextColor(0xFF5B6576);
        field.setTextColor(TEXT);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        String saved = prefs().getString("ip", null);
        if (saved != null) field.setText(saved);
        manual.addView(field, new LinearLayout.LayoutParams(-1, -2));
        TextView where = text(Guide.FIND_IP, 13, MUTED);
        manual.addView(where);
        card(manual);
        secondary("Connect to this address", () -> {
            String s = field.getText().toString().trim();
            if (!s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                Toast.makeText(this, "That doesn't look like an IP address (like 192.168.1.50).", Toast.LENGTH_LONG).show();
                return;
            }
            connect(s);
        });
    }

    private void found(List<String> hosts) {
        page("Step 3 of 6");
        if (hosts.isEmpty()) {
            title("No TV found");
            body("Nothing on this Wi-Fi answered. Check that USB debugging (and Network debugging, if your TV has it) "
                    + "is on, and that the phone and TV are on the same Wi-Fi. You can also type the TV's IP address.");
            primary("Search again", () -> work("Looking for TVs on your Wi-Fi...", () -> {
                final List<String> again = Finder.scan(this);
                runOnUiThread(() -> found(again));
            }));
            secondary("Type the IP address", this::onBackPressed);
            helpButton();
            return;
        }
        title(hosts.size() == 1 ? "Found your TV" : "Found " + hosts.size() + " devices");
        body("Pick your TV. Its IP address is in Settings > Network if you're not sure which one it is.");
        for (final String h : hosts) primary(h, () -> connect(h));
    }

    private void connect(final String host) {
        work("Look at your TV now.\n\nIt asks \"Allow debugging?\" (sometimes \"Allow USB debugging?\"). "
                + "Tick \"Always allow from this computer\" and choose Allow.", () -> {
            if (tv != null) tv.adb.close();
            tv = new Tv(Adb.connect(this, host, Finder.PORT, 90_000));
            ip = host;
            prefs().edit().putString("ip", host).apply();
            runOnUiThread(() -> go(this::yourTv));
        });
    }

    private void yourTv() {
        load(() -> {
            final String model = tv.model(), android = tv.android(), version = tv.quietTvVersion();
            final int free = tv.freeMb(), total = tv.totalMb();
            final boolean hasChanges = !tv.journal().isEmpty();
            if (freeBefore < 0) freeBefore = free;
            runOnUiThread(() -> {
                page("Step 4 of 6");
                title("Connected to your TV");
                card(facts(new String[][] {
                    {"TV", model},
                    {"Android", android},
                    {"Free memory", free + " MB of " + total + " MB"},
                    {"QuietTV", version == null ? "not installed yet" : "version " + version},
                }));
                primary("Clean up my TV", () -> go(this::junk));
                if (hasChanges) secondary("See what QuietTV changed", () -> go(this::manage));
            });
        });
    }

    private void junk() {
        load(() -> {
            final List<Lists.Safe> offer = tv.junkToOffer();
            runOnUiThread(() -> {
                page("Step 5 of 6");
                title("Turn off junk apps");
                if (offer.isEmpty()) {
                    body("Nothing to turn off: your TV is already clean.");
                    primary("Next", () -> go(this::snappier));
                    return;
                }
                body("These come with the TV but aren't needed. They're turned off, not deleted, and you can turn "
                        + "them back on anytime. Untick anything you use.");
                final List<CheckBox> boxes = new ArrayList<>();
                LinearLayout list = new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);
                for (Lists.Safe s : offer) boxes.add(checkRow(list, s.what, s.pkg, s.junk));
                card(list);
                primary("Turn off the ticked apps", () -> {
                    final List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) chosen.add(offer.get(i).pkg);
                    work("Turning off " + chosen.size() + " app(s)...", () -> {
                        for (String p : chosen) tv.turnOff(p);
                        Thread.sleep(3000); // Android saves app states a moment later
                        runOnUiThread(() -> go(this::snappier));
                    });
                });
                secondary("Skip", () -> go(this::snappier));
            });
        });
    }

    private void snappier() {
        load(() -> {
            final List<String> limitable = new ArrayList<>();
            for (String p : new String[] {"com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.netflix.ninja"}) {
                if (tv.isOn(p) && !tv.powerExempt(p)) limitable.add(p);
            }
            runOnUiThread(() -> {
                page("Step 5 of 6");
                title("Make it snappier");
                LinearLayout list = new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);
                final CheckBox anim = checkRow(list, "Turn off animations", "Menus open instantly instead of sliding.", true);
                final CheckBox saver = checkRow(list, "Turn off the screensaver", "One less app running when you pause.", true);
                card(list);
                LinearLayout sleep = new LinearLayout(this);
                sleep.setOrientation(LinearLayout.VERTICAL);
                sleep.addView(text("Turn the TV off when nobody uses the remote for", 15, TEXT));
                final RadioGroup g = new RadioGroup(this);
                final int[] minutes = {0, 5, 10, 30};
                for (int i = 0; i < minutes.length; i++) {
                    RadioButton r = new RadioButton(this);
                    r.setId(i + 1);
                    r.setText(minutes[i] == 0 ? "Don't change it" : minutes[i] + " minutes");
                    r.setTextColor(TEXT);
                    r.setButtonTintList(ColorStateList.valueOf(ACCENT));
                    g.addView(r);
                }
                g.check(1);
                sleep.addView(g);
                sleep.addView(text("Videos keep the TV awake, but a set-top box on HDMI might not.", 13, MUTED));
                card(sleep);
                final List<CheckBox> bgBoxes = new ArrayList<>();
                if (!limitable.isEmpty()) {
                    LinearLayout bg = new LinearLayout(this);
                    bg.setOrientation(LinearLayout.VERTICAL);
                    for (String p : limitable)
                        bgBoxes.add(checkRow(bg, "Close " + Tv.name(p) + " when you're not using it",
                                "It starts by itself and sits in memory. It still opens normally, a little slower.", false));
                    card(bg);
                }
                primary("Apply", () -> {
                    final boolean a = anim.isChecked(), s = saver.isChecked();
                    final int m = minutes[g.getCheckedRadioButtonId() - 1];
                    final List<String> bgChosen = new ArrayList<>();
                    for (int i = 0; i < bgBoxes.size(); i++) if (bgBoxes.get(i).isChecked()) bgChosen.add(limitable.get(i));
                    work("Applying...", () -> {
                        if (a) {
                            tv.setting("global", "window_animation_scale", "0");
                            tv.setting("global", "transition_animation_scale", "0");
                            tv.setting("global", "animator_duration_scale", "0");
                        }
                        if (s) {
                            tv.setting("secure", "screensaver_enabled", "0");
                            tv.setting("secure", "screensaver_activate_on_sleep", "0");
                        }
                        if (m > 0) {
                            tv.setting("system", "screen_off_timeout", String.valueOf(m * 60000));
                            tv.setting("secure", "sleep_timeout", String.valueOf(m * 60000));
                        }
                        for (String p : bgChosen) tv.restrictBackground(p);
                        runOnUiThread(() -> go(this::homeScreen));
                    });
                });
                secondary("Skip", () -> go(this::homeScreen));
            });
        });
    }

    private void homeScreen() {
        load(() -> {
            final List<String> apps = tv.tvApps();
            final List<String> user = tv.userApps();
            runOnUiThread(() -> {
                page("Step 6 of 6");
                title("Your home screen");
                body("Tick the apps you watch. They'll be the tiles on your QuietTV home screen. You can change them "
                        + "later on the TV: hold OK on a tile.");
                List<String> order = new ArrayList<>();
                for (String p : Tv.NAMES.keySet()) if (apps.contains(p)) order.add(p);
                for (String p : apps) if (!order.contains(p)) order.add(p);
                final List<CheckBox> boxes = new ArrayList<>();
                LinearLayout list = new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);
                for (String p : order) {
                    boolean likely = (Tv.NAMES.containsKey(p) || user.contains(p)) && !p.equals("com.android.vending")
                            && !p.equals("com.android.tv.settings") && !p.contains("play.games");
                    boxes.add(checkRow(list, Tv.name(p), p, likely));
                }
                card(list);
                primary("Install QuietTV", () -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) chosen.add(order.get(i));
                    install(chosen, false);
                });
                secondary("Skip the home screen", () -> go(this::done));
            });
        });
    }

    private void install(final List<String> apps, final boolean replace) {
        work("Downloading QuietTV and installing it on your TV...\n\nIf the TV shows a Google Play Protect "
                + "message, choose to install anyway.", () -> {
            byte[] apk = Release.latestQuietTv();
            StringBuilder detail = new StringBuilder();
            Tv.Install r = tv.installQuietTv(apk, replace, detail);
            if (r == Tv.Install.DIFFERENT_KEY) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Replace the QuietTV on your TV?")
                        .setMessage("Your TV has a copy of QuietTV built by someone else (or by you). To install the "
                                + "official one, it has to be removed first. Your home-screen app list will reset.")
                        .setPositiveButton("Replace it", (d, w) -> install(apps, true))
                        .setNegativeButton("Cancel", (d, w) -> current.run())
                        .show());
                return;
            }
            if (r == Tv.Install.FAILED) throw new IOException("The TV wouldn't install QuietTV: " + detail);
            tv.showHome(apps);
            tv.guardOnNow();
            runOnUiThread(() -> go(this::done));
        });
    }

    private void done() {
        load(() -> {
            final int free = tv.freeMb();
            final boolean guard = tv.guardOn();
            runOnUiThread(() -> {
                page(null);
                title("All set");
                body(guard ? "Press Home on your TV remote: QuietTV opens. Your TV's own home screen is still there underneath "
                        + "(\"Original home\" opens it)." : "Your TV is cleaned up.");
                card(facts(new String[][] {
                    {"Free memory before", freeBefore + " MB"},
                    {"Free memory now", free + " MB"},
                }));
                body("Some TVs switch apps back on when they restart. If yours does, open this app and tap "
                        + "\"See what QuietTV changed\", then \"Put back\".");
                primary("See what QuietTV changed", () -> go(this::manage));
                secondary("Close", this::finish);
            });
        });
    }

    private void manage() {
        load(() -> {
            final List<Tv.Change> changes = tv.status();
            runOnUiThread(() -> {
                page(null);
                title("What QuietTV changed");
                int undone = 0;
                LinearLayout list = new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);
                for (Tv.Change c : changes) {
                    if (!c.ok) undone++;
                    String label, now = c.note;
                    if (c.kind.equals("off")) label = Tv.name(c.what) + " turned off";
                    else if (c.kind.equals("bg")) label = Tv.name(c.what) + " limited in the background";
                    else if (c.kind.equals("a11y")) label = "Home button opens QuietTV";
                    else {
                        label = Tv.settingName(c.what);
                        now = "now: " + Tv.settingValue(c.what, c.note);
                    }
                    TextView t = text((c.ok ? "✓  " : "!  ") + label + (c.ok ? "" : "  (" + now + ")"), 14, c.ok ? TEXT : WARN);
                    t.setPadding(0, dp(6), 0, dp(6));
                    list.addView(t);
                }
                if (changes.isEmpty()) list.addView(text("Nothing yet.", 14, MUTED));
                card(list);
                if (undone > 0) {
                    body(undone + " change(s) were undone by the TV, usually after a restart.");
                    primary("Put them back", () -> work("Putting changes back...", () -> {
                        tv.reapply();
                        Thread.sleep(3000);
                        runOnUiThread(this::manage);
                    }));
                }
                secondary("Undo everything", () -> new AlertDialog.Builder(this)
                        .setTitle("Undo everything?")
                        .setMessage("This turns every app back on, restores every setting and removes QuietTV. "
                                + "Your TV goes back to how it was.")
                        .setPositiveButton("Undo everything", (d, w) -> work("Putting your TV back...", () -> {
                            tv.undo();
                            runOnUiThread(() -> {
                                page(null);
                                title("Your TV is back the way it was");
                                body("Everything QuietTV changed has been undone.");
                                primary("Close", this::finish);
                            });
                        }))
                        .setNegativeButton("Cancel", null)
                        .show());
                secondary("Set up again", () -> go(this::yourTv));
                helpButton();
            });
        });
    }

    /** Copies a report to the clipboard and opens the "Setup help" issue form with the TV filled in. */
    private void help() {
        work("Collecting details for your help request...", () -> {
            String report, model = "", ver = "";
            if (tv != null) {
                try {
                    report = tv.report();
                    model = tv.model();
                    ver = tv.android();
                } catch (Exception e) {
                    report = "Couldn't read the TV: " + e.getMessage();
                }
            } else {
                report = "Not connected to the TV yet (IP tried: " + (ip == null ? "none" : "hidden") + ").";
            }
            final String text = report + "\nSent from QuietTV Setup " + version() + " on Android "
                    + android.os.Build.VERSION.RELEASE + ".";
            final String url = ISSUES + "&tv=" + enc(model) + "&android=" + enc(ver);
            runOnUiThread(() -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("QuietTV report", text));
                Toast.makeText(this, "Details copied. Paste them into the \"Report\" box.", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (RuntimeException e) {
                    Toast.makeText(this, url, Toast.LENGTH_LONG).show();
                }
                current.run();
            });
        });
    }

    // ------------------------------------------------------------------ running work off the main thread

    /** Shows a waiting message, runs the task in the background, and shows a friendly error if it fails. */
    private void work(final String waiting, final Task task) {
        workThen(waiting, task, current);
    }

    /** Like work(), for loading a page's data. If it fails, Back goes to the page before. */
    private void load(Task task) {
        workThen("Talking to your TV...", task, this::onBackPressed);
    }

    private void workThen(final String waiting, final Task task, final Runnable back) {
        page(null);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        box.addView(spin, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text(waiting, 17, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(20), 0, 0);
        box.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(60);
        column.addView(box, lp);
        worker.execute(() -> {
            try {
                task.run();
            } catch (final Exception e) {
                runOnUiThread(() -> problem(e, back, () -> workThen(waiting, task, back)));
            }
        });
    }

    private void problem(Exception e, final Runnable back, final Runnable retry) {
        page(null);
        title("That didn't work");
        String why;
        if (e instanceof Adb.NotAllowed) {
            why = e.getMessage() + " Try again, and on the TV tick \"Always allow\" and choose Allow.";
        } else if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.NoRouteToHostException) {
            why = "Couldn't reach the TV. Check that USB debugging (and Network debugging, if there is one) is on, that the "
                    + "phone and TV are on the same Wi-Fi, and that the IP address is right.";
        } else if (e instanceof java.net.UnknownHostException) {
            why = "This phone seems to be offline. QuietTV is downloaded from GitHub, so the phone needs internet.";
        } else {
            why = "Something went wrong: " + e.getMessage();
        }
        body(why);
        primary("Try again", retry);
        if (back != null) secondary("Back", back);
        helpButton();
    }

    // ------------------------------------------------------------------ building blocks

    private void page(String step) {
        column.removeAllViews();
        scroll.scrollTo(0, 0);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        head.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView name = text("QuietTV Setup", 17, TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setPadding(dp(10), 0, 0, 0);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (step != null) head.addView(text(step, 13, MUTED));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = dp(26);
        column.addView(head, hp);
    }

    private void title(String s) {
        TextView t = text(s, 27, TEXT);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setPadding(0, 0, 0, dp(10));
        column.addView(t);
    }

    private void body(String s) {
        TextView t = text(s, 15, MUTED);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dp(4), 0, dp(12));
        column.addView(t);
    }

    private void note(String s) {
        TextView t = text(s, 13, MUTED);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(dp(4), 0, dp(4), dp(10));
        column.addView(t);
    }

    private void card(View content) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), EDGE);
        c.setBackground(g);
        c.addView(content, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(16);
        column.addView(c, lp);
    }

    private View bullets(String head, String[] items) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(text(head, 15, TEXT));
        for (String s : items) {
            TextView t = text("•  " + s, 15, MUTED);
            t.setPadding(0, dp(6), 0, 0);
            l.addView(t);
        }
        return l;
    }

    private View numbered(String[] steps) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView n = text(String.valueOf(i + 1), 14, ON_ACCENT);
            n.setGravity(Gravity.CENTER);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(ACCENT);
            n.setBackground(dot);
            row.addView(n, new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView t = text(steps[i], 15, TEXT);
            t.setLineSpacing(0, 1.15f);
            t.setPadding(dp(12), dp(2), 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            l.addView(row);
        }
        return l;
    }

    private View facts(String[][] rows) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(5), 0, dp(5));
            row.addView(text(r[0], 15, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
            TextView v = text(r[1], 15, TEXT);
            v.setGravity(Gravity.END);
            row.addView(v, new LinearLayout.LayoutParams(0, -2, 1.4f));
            l.addView(row);
        }
        return l;
    }

    private CheckBox checkRow(LinearLayout parent, String title, String detail, boolean checked) {
        CheckBox b = new CheckBox(this);
        b.setText(title);
        b.setTextColor(TEXT);
        b.setTextSize(16);
        b.setChecked(checked);
        b.setButtonTintList(ColorStateList.valueOf(ACCENT));
        b.setPadding(dp(6), dp(8), 0, 0);
        parent.addView(b);
        TextView d = text(detail, 12, MUTED);
        d.setPadding(dp(38), 0, 0, dp(6));
        parent.addView(d);
        return b;
    }

    private void primary(String label, Runnable onClick) {
        button(label, onClick, true);
    }

    private void secondary(String label, Runnable onClick) {
        button(label, onClick, false);
    }

    private void helpButton() {
        TextView t = text("Stuck? Get help from the QuietTV community", 14, ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(18), 0, dp(8));
        t.setOnClickListener(v -> help());
        column.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private void button(String label, final Runnable onClick, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(primary ? ACCENT : 0x00000000);
        if (!primary) g.setStroke(dp(1), 0xFF3A4558);
        b.setBackground(g);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        column.addView(b, lp);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
