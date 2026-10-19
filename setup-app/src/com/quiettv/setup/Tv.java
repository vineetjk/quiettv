package com.quiettv.setup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Everything QuietTV Setup does to a TV, the same way quiettv.sh does it. Each change is written to
 * the same list on the TV (/data/local/tmp/quiettv-journal.txt) before it's made, so the phone app and
 * the script can be mixed: `sh quiettv.sh status/reapply/undo` work on changes made from the phone.
 * Every package name and value is checked against a strict pattern before it reaches a shell command.
 */
final class Tv {
    static final String JOURNAL = "/data/local/tmp/quiettv-journal.txt";
    static final String QUIETTV = "com.quiettv.home";
    static final String GUARD = QUIETTV + "/" + QUIETTV + ".HomeGuard";
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.]{1,100}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_.:/-]{1,500}");

    final Adb adb;

    Tv(Adb adb) {
        this.adb = adb;
    }

    // ------------------------------------------------------------------ facts

    String prop(String name) throws Exception {
        return adb.shell("getprop " + name).trim();
    }

    String model() throws Exception {
        return (prop("ro.product.manufacturer") + " " + prop("ro.product.model")).trim();
    }

    String android() throws Exception {
        return prop("ro.build.version.release");
    }

    int freeMb() throws Exception {
        return meminfo("MemAvailable");
    }

    int totalMb() throws Exception {
        return meminfo("MemTotal");
    }

    private int meminfo(String field) throws Exception {
        for (String l : adb.shell("cat /proc/meminfo").split("\n")) {
            if (l.startsWith(field + ":")) return Integer.parseInt(l.replaceAll("[^0-9]", "")) / 1024;
        }
        return -1;
    }

    boolean isOn(String pkg) throws Exception {
        return hasLine(adb.shell("pm list packages -e --user 0 " + check(pkg)), "package:" + pkg);
    }

    boolean isOff(String pkg) throws Exception {
        return hasLine(adb.shell("pm list packages -d --user 0 " + check(pkg)), "package:" + pkg);
    }

    boolean installed(String pkg) throws Exception {
        return hasLine(adb.shell("pm list packages " + check(pkg)), "package:" + pkg);
    }

    /** Never turned off: the protected list, QuietTV itself, always-running apps, and unreadable ones. */
    boolean isProtected(String pkg) throws Exception {
        if (pkg.equals(QUIETTV) || Lists.isProtected(pkg)) return true;
        String flags = "";
        for (String l : adb.shell("dumpsys package " + check(pkg)).split("\n")) {
            if (l.contains("pkgFlags=[")) {
                flags = l;
                break;
            }
        }
        return flags.isEmpty() || flags.contains("PERSISTENT");
    }

    String quietTvVersion() throws Exception {
        for (String l : adb.shell("dumpsys package " + QUIETTV).split("\n")) {
            int i = l.indexOf("versionName=");
            if (i >= 0) return l.substring(i + 12).trim();
        }
        return null;
    }

    boolean guardOn() throws Exception {
        for (String s : adb.shell("settings get secure enabled_accessibility_services").trim().split(":")) {
            if (s.equals(GUARD)) return true;
        }
        return false;
    }

    /** Apps with a TV launcher icon, as package names. */
    List<String> tvApps() throws Exception {
        List<String> out = new ArrayList<>();
        String q = adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.LEANBACK_LAUNCHER");
        for (String l : q.split("\n")) {
            l = l.trim();
            int slash = l.indexOf('/');
            if (slash > 0) {
                String p = l.substring(0, slash);
                if (PKG.matcher(p).matches() && !out.contains(p) && !p.equals(QUIETTV)) out.add(p);
            }
        }
        return out;
    }

    List<String> userApps() throws Exception {
        List<String> out = new ArrayList<>();
        for (String l : adb.shell("pm list packages -3").split("\n")) {
            if (l.startsWith("package:")) out.add(l.substring(8).trim());
        }
        return out;
    }

    /** Safe-list apps that are installed and on right now. */
    List<Lists.Safe> junkToOffer() throws Exception {
        String on = adb.shell("pm list packages -e --user 0");
        List<Lists.Safe> out = new ArrayList<>();
        for (Lists.Safe s : Lists.SAFE) {
            if (hasLine(on, "package:" + s.pkg) && !isProtected(s.pkg)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ changes (recorded first)

    private void note(String line) throws Exception {
        if (!line.matches("[A-Za-z0-9_.:/ -]+")) throw new IllegalArgumentException("unsafe journal line");
        adb.shell("echo '" + line + "' >> " + JOURNAL);
    }

    /** Turns an app off (never uninstalls). Returns true if it's now off. */
    boolean turnOff(String pkg) throws Exception {
        check(pkg);
        if (isProtected(pkg)) return false;
        if (!isOn(pkg)) return isOff(pkg);
        note("off " + pkg);
        adb.shell("pm disable-user --user 0 " + pkg);
        return isOff(pkg);
    }

    /** Sets a global/secure/system setting, remembering its old value. */
    void setting(String ns, String key, String value) throws Exception {
        if (!ns.matches("global|secure|system") || !KEY.matcher(key).matches() || !VALUE.matcher(value).matches())
            throw new IllegalArgumentException("unsafe setting");
        String old = adb.shell("settings get " + ns + " " + key).trim();
        if (old.equals(value)) return;
        if (old.isEmpty() || !VALUE.matcher(old).matches()) old = "null";
        note("setting " + ns + " " + key + " " + old + " " + value);
        adb.shell("settings put " + ns + " " + key + " " + value);
    }

    /** True if the TV maker exempted the app from power saving (then a background limit does nothing). */
    boolean powerExempt(String pkg) throws Exception {
        return adb.shell("dumpsys deviceidle whitelist").contains("," + check(pkg) + ",");
    }

    /** Lets Android close the app when it's idle in the background. */
    void restrictBackground(String pkg) throws Exception {
        check(pkg);
        String now = adb.shell("cmd appops get " + pkg + " RUN_ANY_IN_BACKGROUND");
        if (now.contains("ignore")) return;
        String old = now.contains("RUN_ANY_IN_BACKGROUND: allow") ? "allow" : "default";
        note("bg " + pkg + " " + old);
        adb.shell("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore");
    }

    /** Result of installing QuietTV on the TV. */
    enum Install { DONE, DIFFERENT_KEY, FAILED }

    /** Installs (or updates) the QuietTV app. DIFFERENT_KEY means a copy signed by someone else is there. */
    Install installQuietTv(byte[] apk, boolean replaceDifferentKey, StringBuilder detail) throws Exception {
        boolean had = installed(QUIETTV);
        if (!had) note("installed " + QUIETTV);
        String r = adb.install(apk, true);
        if (r.contains("UPDATE_INCOMPATIBLE") || r.contains("INCONSISTENT_CERTIFICATES") || r.contains("SIGNATURES_DO_NOT_MATCH")) {
            if (!replaceDifferentKey) return Install.DIFFERENT_KEY;
            adb.shell("pm uninstall " + QUIETTV);
            r = adb.install(apk, false);
        }
        detail.append(r);
        if (!r.contains("Success")) return Install.FAILED;
        adb.shell("appops set " + QUIETTV + " ACCESS_RESTRICTED_SETTINGS allow");
        return Install.DONE;
    }

    /** Puts the chosen apps on QuietTV's home screen and shows it (as a home task). */
    void showHome(List<String> apps) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String p : apps) {
            check(p);
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        String cmd = "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n "
                + QUIETTV + "/.HomeActivity --es stamp " + System.currentTimeMillis();
        if (sb.length() > 0) cmd += " --es apps " + sb;
        adb.shell(cmd);
    }

    /** Turns on QuietTV's Home-button guard (an accessibility service), keeping any others. */
    void guardOnNow() throws Exception {
        if (guardOn()) return;
        String old = adb.shell("settings get secure enabled_accessibility_services").trim();
        String oldOn = adb.shell("settings get secure accessibility_enabled").trim();
        if (old.isEmpty()) old = "null";
        if (!VALUE.matcher(old).matches() || !VALUE.matcher(oldOn).matches()) throw new IllegalArgumentException("unexpected setting");
        note("a11y " + old + " " + oldOn);
        String value = old.equals("null") ? GUARD : old + ":" + GUARD;
        adb.shell("settings put secure enabled_accessibility_services " + value);
        adb.shell("settings put secure accessibility_enabled 1");
    }

    // ------------------------------------------------------------------ status, reapply, undo

    /** One recorded change and whether it's still in place. */
    static final class Change {
        final String kind, what;
        final boolean ok;
        final String note;

        Change(String kind, String what, boolean ok, String note) {
            this.kind = kind;
            this.what = what;
            this.ok = ok;
            this.note = note;
        }
    }

    List<String[]> journal() throws Exception {
        List<String[]> out = new ArrayList<>();
        for (String l : adb.shell("cat " + JOURNAL + " 2>/dev/null").split("\n")) {
            String[] f = l.trim().split("\\s+");
            if (f.length >= 2) out.add(f);
        }
        return out;
    }

    List<Change> status() throws Exception {
        List<Change> out = new ArrayList<>();
        for (String[] f : journal()) {
            switch (f[0]) {
                case "off":
                    if (!PKG.matcher(f[1]).matches()) break;
                    boolean off = isOff(f[1]);
                    out.add(new Change("off", f[1], off, off ? "off" : "back on"));
                    break;
                case "setting":
                    if (f.length < 5 || !validSetting(f[1], f[2])) break;
                    String now = adb.shell("settings get " + f[1] + " " + f[2]).trim();
                    out.add(new Change("setting", f[1] + " " + f[2], now.equals(f[4]), now));
                    break;
                case "bg":
                    boolean r = adb.shell("cmd appops get " + check(f[1]) + " RUN_ANY_IN_BACKGROUND").contains("ignore");
                    out.add(new Change("bg", f[1], r, r ? "limited" : "not limited"));
                    break;
                case "a11y":
                    boolean g = guardOn();
                    out.add(new Change("a11y", "Home button", g, g ? "on" : "off"));
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    /** Puts back recorded changes the TV undid. Returns how many it fixed. */
    int reapply() throws Exception {
        int fixed = 0;
        for (Change c : status()) {
            if (c.ok) continue;
            switch (c.kind) {
                case "off":
                    if (!isProtected(c.what)) {
                        adb.shell("pm disable-user --user 0 " + c.what);
                        fixed++;
                    }
                    break;
                case "setting":
                    for (String[] f : journal()) {
                        if (f[0].equals("setting") && f.length >= 5 && (f[1] + " " + f[2]).equals(c.what)
                                && validSetting(f[1], f[2]) && VALUE.matcher(f[4]).matches()) {
                            adb.shell("settings put " + f[1] + " " + f[2] + " " + f[4]);
                            fixed++;
                            break;
                        }
                    }
                    break;
                case "bg":
                    adb.shell("cmd appops set " + c.what + " RUN_ANY_IN_BACKGROUND ignore");
                    fixed++;
                    break;
                case "a11y":
                    String old = adb.shell("settings get secure enabled_accessibility_services").trim();
                    String value = old.isEmpty() || old.equals("null") ? GUARD : old + ":" + GUARD;
                    if (VALUE.matcher(value).matches()) {
                        adb.shell("settings put secure enabled_accessibility_services " + value);
                        adb.shell("settings put secure accessibility_enabled 1");
                        fixed++;
                    }
                    break;
                default:
                    break;
            }
        }
        return fixed;
    }

    /** Puts everything back, newest change first, then removes QuietTV and the list. */
    void undo() throws Exception {
        List<String[]> list = journal();
        Collections.reverse(list);
        for (String[] f : list) {
            switch (f[0]) {
                case "off":
                    if (PKG.matcher(f[1]).matches()) adb.shell("pm enable " + f[1]);
                    break;
                case "setting":
                    if (f.length < 4 || !f[1].matches("global|secure|system") || !KEY.matcher(f[2]).matches()) break;
                    if (f[3].equals("null")) adb.shell("settings delete " + f[1] + " " + f[2]);
                    else if (VALUE.matcher(f[3]).matches()) adb.shell("settings put " + f[1] + " " + f[2] + " " + f[3]);
                    break;
                case "bg":
                    if (PKG.matcher(f[1]).matches() && f.length >= 3 && f[2].matches("allow|default|ignore"))
                        adb.shell("cmd appops set " + f[1] + " RUN_ANY_IN_BACKGROUND " + f[2]);
                    break;
                case "a11y":
                    if (f[1].equals("null")) adb.shell("settings delete secure enabled_accessibility_services");
                    else if (VALUE.matcher(f[1]).matches()) adb.shell("settings put secure enabled_accessibility_services " + f[1]);
                    if (f.length >= 3 && f[2].matches("[0-9]")) adb.shell("settings put secure accessibility_enabled " + f[2]);
                    break;
                case "installed":
                    if (f[1].equals(QUIETTV)) adb.shell("pm uninstall " + QUIETTV);
                    break;
                default:
                    break;
            }
        }
        adb.shell("rm -f " + JOURNAL);
        adb.shell("input keyevent KEYCODE_HOME");
    }

    /** The same report as `quiettv.sh report`: for pasting into a GitHub issue. No IP address. */
    String report() throws Exception {
        StringBuilder r = new StringBuilder("### QuietTV report (from QuietTV Setup)\n\n```\n");
        r.append("tv:          ").append(model()).append(" (").append(prop("ro.product.device")).append(")\n");
        r.append("android:     ").append(android()).append(" (SDK ").append(prop("ro.build.version.sdk"))
                .append("), build ").append(prop("ro.build.id")).append('\n');
        r.append("memory:      ").append(freeMb()).append(" MB free of ").append(totalMb()).append(" MB\n");
        String v = quietTvVersion();
        r.append("quiettv:     ").append(v == null ? "not installed" : v).append('\n');
        r.append("home guard:  ").append(guardOn() ? "on" : "off").append('\n');
        String home = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").trim();
        r.append("tv home:     ").append(home.contains("\n") ? home.substring(home.lastIndexOf('\n') + 1) : home).append('\n');
        r.append("crashes:     ").append(count(adb.shell("logcat -d -b crash"), "FATAL EXCEPTION IN SYSTEM")).append(" system crash(es) since boot\n```\n\n");
        r.append("**Changes recorded by QuietTV:**\n\n```\n");
        for (Change c : status()) r.append(c.ok ? "ok       " : "CHANGED  ").append(c.kind).append(' ').append(c.what).append(" (").append(c.note).append(")\n");
        r.append("```\n");
        return r.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean validSetting(String ns, String key) {
        return ns.matches("global|secure|system") && KEY.matcher(key).matches();
    }

    private static String check(String pkg) {
        if (!PKG.matcher(pkg).matches()) throw new IllegalArgumentException("not a package name: " + pkg);
        return pkg;
    }

    private static boolean hasLine(String text, String line) {
        for (String l : text.split("\n")) if (l.trim().equals(line)) return true;
        return false;
    }

    private static int count(String text, String what) {
        int n = 0, i = 0;
        while ((i = text.indexOf(what, i)) >= 0) {
            n++;
            i += what.length();
        }
        return n;
    }

    /** Friendly names for common TV apps (adb can't read app labels). */
    static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put("com.google.android.youtube.tv", "YouTube");
        NAMES.put("com.netflix.ninja", "Netflix");
        NAMES.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        NAMES.put("com.disney.disneyplus", "Disney+");
        NAMES.put("in.startv.hotstar", "JioHotstar");
        NAMES.put("com.hulu.livingroomplus", "Hulu");
        NAMES.put("com.wbd.stream", "Max");
        NAMES.put("com.apple.atve.androidtv.appletv", "Apple TV");
        NAMES.put("com.spotify.tv.android", "Spotify");
        NAMES.put("com.plexapp.android", "Plex");
        NAMES.put("org.xbmc.kodi", "Kodi");
        NAMES.put("com.graymatrix.did", "ZEE5");
        NAMES.put("com.suntv.sunnxt", "Sun NXT");
        NAMES.put("tv.airtel.xstream.tvapp", "Airtel Xstream");
        NAMES.put("com.jio.media.stb.ondemand", "JioCinema");
        NAMES.put("com.sonyliv", "SonyLIV");
        NAMES.put("com.google.android.youtube.tvmusic", "YouTube Music");
        NAMES.put("com.google.android.videos", "Google TV");
        NAMES.put("com.android.vending", "Play Store");
        NAMES.put("com.android.tv.settings", "Settings");
        NAMES.put("com.google.android.play.games", "Play Games");
    }

    /** A readable name: a known app, else the start of its safe-list description, else its package's last part. */
    static String name(String pkg) {
        String n = NAMES.get(pkg);
        if (n != null) return n;
        for (Lists.Safe x : Lists.SAFE) {
            if (x.pkg.equals(pkg)) {
                String w = x.what.replace("\"", "");
                for (String cut : new String[] {":", ",", " ("}) {
                    int i = w.indexOf(cut);
                    if (i > 0) w = w.substring(0, i);
                }
                return w.trim();
            }
        }
        String last = pkg.substring(pkg.lastIndexOf('.') + 1);
        return Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    /** A readable name for a recorded setting ("global window_animation_scale"). */
    static String settingName(String nsKey) {
        String key = nsKey.substring(nsKey.indexOf(' ') + 1);
        switch (key) {
            case "window_animation_scale": return "Window animations off";
            case "transition_animation_scale": return "Transition animations off";
            case "animator_duration_scale": return "Animator animations off";
            case "screensaver_enabled": return "Screensaver off";
            case "screensaver_activate_on_sleep": return "No screensaver before sleep";
            case "screensaver_activate_on_dock": return "No screensaver when docked";
            case "screen_off_timeout": return "Screen turns off when idle";
            case "sleep_timeout": return "TV sleeps when idle";
            default: return key.replace('_', ' ');
        }
    }

    /** A setting's value in words: timeouts in minutes, "never" for the maximum. */
    static String settingValue(String nsKey, String value) {
        if (!nsKey.endsWith("timeout")) return value;
        try {
            long ms = Long.parseLong(value);
            if (ms >= Integer.MAX_VALUE || ms < 0) return "never";
            return (ms / 60000) + " min";
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
