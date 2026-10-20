package com.quiettv.home;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A GitHub user's public contribution calendar, for Dev mode. It reads the same public page github.com
 * shows on profiles (https://github.com/users/NAME/contributions), so no account or token is needed.
 * Only used when Dev mode is on; the last result is cached so the graph shows instantly and offline.
 */
final class GitHub {
    static final long MAX_AGE = 3L * 60 * 60 * 1000; // refresh at most every 3 hours
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}");
    private static final Pattern TD = Pattern.compile("<td\\b[^>]*\\bdata-date=\"(\\d{4}-\\d{2}-\\d{2})\"[^>]*>");
    private static final Pattern ID = Pattern.compile("\\bid=\"([^\"]+)\"");
    private static final Pattern LEVEL = Pattern.compile("\\bdata-level=\"(\\d)\"");
    private static final Pattern TIP = Pattern.compile("<tool-tip\\b[^>]*\\bfor=\"([^\"]+)\"[^>]*>([^<]*)</tool-tip>");
    private static final Pattern TOTAL = Pattern.compile("([0-9,]+)\\s+contributions?\\s+in the last year");
    private static volatile boolean fetching;

    static final class Day {
        final String date;
        final int level, count;

        Day(String date, int level, int count) {
            this.date = date;
            this.level = level;
            this.count = count;
        }
    }

    static final class Calendar {
        final String user;
        final int total;
        final long fetchedAt;
        final List<Day> days; // oldest first; the last one is today
        int today, current, longest;

        Calendar(String user, int total, long fetchedAt, List<Day> days) {
            this.user = user;
            this.total = total;
            this.fetchedAt = fetchedAt;
            this.days = days;
            streaks();
        }

        /** Current streak counts back from today, or from yesterday if today has nothing yet (like GitHub). */
        private void streaks() {
            int run = 0;
            for (Day d : days) {
                run = d.count > 0 || d.level > 0 ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
            if (days.isEmpty()) return;
            Day last = days.get(days.size() - 1);
            today = last.count;
            int i = days.size() - 1;
            if (last.count == 0 && last.level == 0) i--;
            while (i >= 0 && (days.get(i).count > 0 || days.get(i).level > 0)) {
                current++;
                i--;
            }
        }
    }

    private GitHub() {}

    static boolean validUser(String u) {
        return u != null && USER.matcher(u).matches();
    }

    /** True if a fetch is due: Dev mode is on, a name is set, and the cache is missing or old. */
    static boolean due(Context c, String user) {
        if (!validUser(user)) return false;
        Calendar cal = cached(c, user);
        return cal == null || System.currentTimeMillis() - cal.fetchedAt > MAX_AGE;
    }

    /** Fetches in the background and calls done on the main thread (with null on failure). */
    static void refresh(final Context c, final String user, final Callback done) {
        if (fetching || !validUser(user)) return;
        fetching = true;
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            Calendar cal = null;
            try {
                cal = fetch(c, user);
            } catch (Exception ignored) {
            } finally {
                fetching = false;
            }
            final Calendar result = cal;
            main.post(() -> done.loaded(result));
        }, "quiettv-github").start();
    }

    interface Callback { void loaded(Calendar cal); }

    static Calendar fetch(Context c, String user) throws IOException {
        if (!validUser(user)) throw new IOException("not a GitHub username");
        HttpURLConnection h = (HttpURLConnection) new URL("https://github.com/users/" + user + "/contributions").openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(15_000);
        h.setRequestProperty("User-Agent", "QuietTV (Android TV home screen)");
        try {
            int code = h.getResponseCode();
            if (code == 404) throw new IOException("No GitHub user called " + user);
            if (code != 200) throw new IOException("GitHub answered " + code);
            String html = read(h.getInputStream(), 3 << 20);
            Calendar cal = parse(user, html, System.currentTimeMillis());
            if (cal.days.isEmpty()) throw new IOException("GitHub's page has changed; QuietTV needs an update");
            save(c, cal);
            return cal;
        } finally {
            h.disconnect();
        }
    }

    static Calendar parse(String user, String html, long now) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher tip = TIP.matcher(html);
        while (tip.find()) {
            String text = tip.group(2).trim();
            int n = 0;
            Matcher num = Pattern.compile("^([0-9,]+) contribution").matcher(text);
            if (num.find()) n = Integer.parseInt(num.group(1).replace(",", ""));
            counts.put(tip.group(1), n);
        }
        List<Day> days = new ArrayList<>();
        Matcher td = TD.matcher(html);
        while (td.find()) {
            String tag = td.group(0);
            Matcher id = ID.matcher(tag), lv = LEVEL.matcher(tag);
            int level = lv.find() ? Integer.parseInt(lv.group(1)) : 0;
            Integer n = id.find() ? counts.get(id.group(1)) : null;
            days.add(new Day(td.group(1), Math.max(0, Math.min(4, level)), n == null ? (level > 0 ? -1 : 0) : n));
        }
        Collections.sort(days, (a, b) -> a.date.compareTo(b.date));
        int total = 0;
        Matcher t = TOTAL.matcher(html.replaceAll("\\s+", " "));
        if (t.find()) total = Integer.parseInt(t.group(1).replace(",", ""));
        else for (Day d : days) total += Math.max(0, d.count);
        return new Calendar(user, total, now, days);
    }

    // ------------------------------------------------------------------ cache (one small text file)

    private static File file(Context c) {
        return new File(c.getFilesDir(), "contributions.txt");
    }

    private static void save(Context c, Calendar cal) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(cal.user).append(' ').append(cal.total).append(' ').append(cal.fetchedAt).append('\n');
        for (Day d : cal.days) sb.append(d.date).append(' ').append(d.level).append(' ').append(d.count).append('\n');
        File tmp = new File(c.getFilesDir(), "contributions.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file(c))) throw new IOException("couldn't save");
    }

    /** The cached calendar for this user, or null. */
    static Calendar cached(Context c, String user) {
        try (InputStream in = new FileInputStream(file(c))) {
            String[] lines = read(in, 1 << 20).split("\n");
            String[] head = lines[0].split(" ");
            if (head.length < 3 || !head[0].equalsIgnoreCase(user)) return null;
            List<Day> days = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String[] f = lines[i].split(" ");
                if (f.length == 3) days.add(new Day(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2])));
            }
            return new Calendar(head[0], Integer.parseInt(head[1]), Long.parseLong(head[2]), days);
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) throw new IOException("page too large");
        }
        return out.toString("UTF-8");
    }
}
