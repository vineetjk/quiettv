package com.quiettv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Puts the user's changes back after a restart: some TVs (Acer, for one) turn disabled apps back on at
 * every boot. It reads the list of changes `quiettv.sh` wrote on the TV and re-applies only these kinds:
 *   off <package>                       the app stays turned off
 *   setting <ns> <key> <old> <new>      a global/secure/system setting keeps its value
 *   bg <package> <old>                  the app stays restricted in the background
 *   a11y <old> <old-enabled>            QuietTV's Home guard stays on
 * Every value is checked against a strict pattern, and protected apps are never turned off, so a damaged
 * or tampered list can't make it run anything else.
 */
final class Keeper {
    static final String JOURNAL = "/data/local/tmp/quiettv-journal.txt";
    private static final String TAG = "QuietTV";
    private static final int PORT = 5555;
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern NS = Pattern.compile("global|secure|system");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.]{1,100}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_.:/-]{1,500}");
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final long DAY = 24L * 60 * 60 * 1000;

    private Keeper() {}

    static boolean enabled(Context c) {
        return Apps.prefs(c).getBoolean("keeper", false);
    }

    /** After a restart: run once soon, and again a little later in case the TV re-enables apps late. */
    static void onGuardStarted(Context c) {
        if (!enabled(c)) return;
        final Context app = c.getApplicationContext();
        Handler h = new Handler(Looper.getMainLooper());
        long sinceBoot = SystemClock.elapsedRealtime();
        if (sinceBoot < 5 * 60 * 1000) {
            h.postDelayed(() -> runInBackground(app), 20_000);
            h.postDelayed(() -> runInBackground(app), 150_000);
        } else {
            runInBackground(app);
        }
    }

    /** Also once a day while the TV is used. This keeps the TV's approval of QuietTV's key fresh: Android
     *  forgets "Always allow" after 7 days without a connection. */
    static void maybeRun(Context c) {
        if (!enabled(c)) return;
        if (System.currentTimeMillis() - Apps.prefs(c).getLong("keeper_time", 0) > DAY) runInBackground(c.getApplicationContext());
    }

    static void runInBackground(final Context c) {
        if (!running.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                record(c, run(c, false));
            } catch (AdbClient.NotAllowed e) {
                record(c, "Not allowed: " + e.getMessage() + " Run: sh quiettv.sh keep <TV_IP>");
            } catch (Exception e) {
                record(c, "Couldn't connect to the TV's debugging port: " + e.getMessage()
                        + ". Is network debugging still on?");
            } finally {
                running.set(false);
            }
        }, "quiettv-keeper").start();
    }

    /** First connection: the TV asks "Allow debugging?". Returns what happened, in words. Blocking. */
    static String pair(Context c) {
        try (AdbClient adb = AdbClient.connect(c, PORT, true, 90_000)) {
            Apps.prefs(c).edit().putBoolean("keeper", true).apply();
            String result = apply(c, adb);
            record(c, result);
            return "Connected. " + result;
        } catch (AdbClient.NotAllowed e) {
            return e.getMessage();
        } catch (Exception e) {
            return "Couldn't connect to the TV's debugging port (" + e.getMessage()
                    + "). Network debugging must be on.";
        }
    }

    private static String run(Context c, boolean mayAsk) throws Exception {
        try (AdbClient adb = AdbClient.connect(c, PORT, mayAsk, 60_000)) {
            return apply(c, adb);
        }
    }

    private static String apply(Context c, AdbClient adb) throws Exception {
        String list = adb.shell("cat " + JOURNAL + " 2>/dev/null", 10_000);
        int fixed = 0, ok = 0, skipped = 0;
        for (String line : list.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 2) continue;
            int r;
            switch (f[0]) {
                case "off": r = keepOff(adb, f[1]); break;
                case "setting": r = f.length >= 5 ? keepSetting(adb, f[1], f[2], f[4]) : -1; break;
                case "bg": r = keepRestricted(adb, f[1]); break;
                case "a11y": r = keepGuard(c, adb); break;
                default: continue;
            }
            if (r > 0) fixed++;
            else if (r == 0) ok++;
            else skipped++;
        }
        String s = fixed == 0 ? "Everything was still in place (" + ok + " changes)."
                : "Put back " + fixed + " change" + (fixed == 1 ? "" : "s") + " the TV had undone (" + ok + " were fine).";
        if (skipped > 0) s += " Skipped " + skipped + " line(s) it doesn't trust.";
        return s;
    }

    /** 1 = fixed, 0 = already fine, -1 = refused. */
    private static int keepOff(AdbClient adb, String pkg) throws Exception {
        if (!PKG.matcher(pkg).matches() || Protected.matches(pkg) || pkg.equals("com.quiettv.home")) return -1;
        String on = adb.shell("pm list packages -e --user 0 " + pkg, 10_000);
        if (!hasLine(on, "package:" + pkg)) return 0;
        adb.shell("pm disable-user --user 0 " + pkg, 15_000);
        Log.i(TAG, "keeper: turned " + pkg + " off again");
        return 1;
    }

    private static int keepSetting(AdbClient adb, String ns, String key, String value) throws Exception {
        if (!NS.matcher(ns).matches() || !KEY.matcher(key).matches() || !VALUE.matcher(value).matches()) return -1;
        if (key.startsWith("enabled_accessibility") || key.startsWith("adb_")) return -1;
        String now = adb.shell("settings get " + ns + " " + key, 10_000).trim();
        if (now.equals(value)) return 0;
        adb.shell("settings put " + ns + " " + key + " " + value, 10_000);
        Log.i(TAG, "keeper: " + ns + " " + key + " back to " + value);
        return 1;
    }

    private static int keepRestricted(AdbClient adb, String pkg) throws Exception {
        if (!PKG.matcher(pkg).matches() || Protected.matches(pkg)) return -1;
        String now = adb.shell("cmd appops get " + pkg + " RUN_ANY_IN_BACKGROUND", 10_000);
        if (now.contains("ignore")) return 0;
        adb.shell("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore", 10_000);
        Log.i(TAG, "keeper: " + pkg + " restricted in the background again");
        return 1;
    }

    private static int keepGuard(Context c, AdbClient adb) throws Exception {
        String guard = c.getPackageName() + "/" + HomeGuard.class.getName();
        String now = adb.shell("settings get secure enabled_accessibility_services", 10_000).trim();
        for (String s : now.split(":")) if (s.equals(guard)) return 0;
        String value = now.isEmpty() || now.equals("null") ? guard : now + ":" + guard;
        if (!VALUE.matcher(value).matches()) return -1;
        adb.shell("settings put secure enabled_accessibility_services " + value, 10_000);
        adb.shell("settings put secure accessibility_enabled 1", 10_000);
        Log.i(TAG, "keeper: Home guard turned back on");
        return 1;
    }

    private static boolean hasLine(String text, String line) {
        for (String l : text.split("\n")) if (l.trim().equals(line)) return true;
        return false;
    }

    private static void record(Context c, String result) {
        Log.i(TAG, "keeper: " + result);
        SharedPreferences.Editor e = Apps.prefs(c).edit().putString("keeper_result", result);
        if (!result.startsWith("Not allowed") && !result.startsWith("Couldn't")) e.putLong("keeper_time", System.currentTimeMillis());
        e.apply();
    }

    static String lastResult(Context c) {
        SharedPreferences p = Apps.prefs(c);
        long t = p.getLong("keeper_time", 0);
        String when = t == 0 ? "never" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(t));
        return "Last check: " + when + ". " + p.getString("keeper_result", "");
    }
}
