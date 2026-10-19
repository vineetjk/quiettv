package com.quiettv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Everything about installed apps: what can be launched, their banners, and the saved home list. */
final class Apps {
    static final String PREFS = "quiettv";

    /** Popular streaming apps, used only to fill the home row the first time. */
    private static final List<String> POPULAR = Arrays.asList(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "in.startv.hotstar", "com.hulu.livingroomplus", "com.wbd.stream",
            "com.apple.atve.androidtv.appletv", "com.spotify.tv.android", "com.plexapp.android",
            "org.xbmc.kodi", "com.google.android.youtube.tvmusic");

    private Apps() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The launch intent for an app: the TV one first, then the phone one. */
    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    /** Every app with a TV launcher entry (plus phone-only apps the user installed), sorted by name. */
    static List<String> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> pkgs = new LinkedHashSet<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                boolean tv = Intent.CATEGORY_LEANBACK_LAUNCHER.equals(cat);
                boolean userApp = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                if (tv || userApp) pkgs.add(ai.packageName);
            }
        }
        pkgs.remove(c.getPackageName());
        List<String> out = new ArrayList<>(pkgs);
        final Collator col = Collator.getInstance();
        Collections.sort(out, (a, b) -> col.compare(label(c, a), label(c, b)));
        return out;
    }

    static boolean installed(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean userInstalled(Context c, String pkg) {
        try {
            return (c.getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static String label(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** The app's TV banner, drawn once at tile size so huge banners don't waste memory. Null if it has none. */
    static Drawable banner(Context c, String pkg, int w, int h) {
        PackageManager pm = c.getPackageManager();
        Drawable d = null;
        Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (li != null) {
            ResolveInfo ri = pm.resolveActivity(li, 0);
            if (ri != null) d = ri.activityInfo.loadBanner(pm);
        }
        if (d == null) {
            try {
                d = pm.getApplicationInfo(pkg, 0).loadBanner(pm);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return d == null ? null : render(c, d, w, h, true);
    }

    static Drawable icon(Context c, String pkg, int size) {
        try {
            Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            return render(c, d, size, size, false);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Draws a drawable into a bitmap of exactly w x h, center-cropped (crop) or fitted. */
    private static Drawable render(Context c, Drawable d, int w, int h, boolean crop) {
        try {
            int iw = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : w;
            int ih = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : h;
            float s = crop ? Math.max((float) w / iw, (float) h / ih) : Math.min((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            Rect old = new Rect(d.getBounds());
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            d.draw(cv);
            d.setBounds(old);
            return new BitmapDrawable(c.getResources(), b);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- The saved list of apps on the home screen ----

    static List<String> homeList(Context c) {
        String saved = prefs(c).getString("apps", null);
        List<String> out = new ArrayList<>();
        if (saved == null) {
            out.addAll(defaults(c));
            saveHomeList(c, out);
            return out;
        }
        for (String p : saved.split(",")) {
            p = p.trim();
            if (!p.isEmpty() && !out.contains(p) && installed(c, p) && launchIntent(c.getPackageManager(), p) != null) out.add(p);
        }
        return out;
    }

    static void saveHomeList(Context c, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        prefs(c).edit().putString("apps", sb.toString()).apply();
    }

    /** First run: popular streaming apps that are installed, then apps the user installed. */
    private static List<String> defaults(Context c) {
        List<String> all = launchable(c);
        List<String> out = new ArrayList<>();
        for (String p : POPULAR) if (all.contains(p)) out.add(p);
        for (String p : all) if (!out.contains(p) && userInstalled(c, p)) out.add(p);
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    /** The system's default home screen (the TV's own launcher), or null if it is us or unknown. */
    static ComponentName stockHome(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        if (c.getPackageName().equals(ri.activityInfo.packageName)) return null;
        if ("android".equals(ri.activityInfo.packageName)) return null; // the "choose a home app" dialog
        return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
    }
}
