package com.quiettv.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Shows QuietTV instead of the TV's own home screen, without turning that home screen off
 * (turning it off crashes many Google TVs). Three things bring QuietTV up:
 *  - the Home button (caught here before Android acts on it),
 *  - the TV's home screen window appearing anyway (after a restart, or from another app),
 *  - starting QuietTV as a "home" task, so leaving an app returns to it without the TV's home flashing.
 * It looks only at the Home key and at which app's window opened, never at what's on screen.
 */
public class HomeGuard extends AccessibilityService {
    static final String TAG = "QuietTV";
    private final Handler main = new Handler(Looper.getMainLooper());
    private ComponentName stock;
    private static volatile long lastShown;

    @Override protected void onServiceConnected() {
        stock = Apps.stockHome(this);
        Log.i(TAG, "guard started, TV home = " + stock);
        // Right after a restart the TV's home screen is already up before this service starts. After an
        // update or crash, put QuietTV back if it was what the user was looking at.
        boolean booting = SystemClock.elapsedRealtime() < 3 * 60 * 1000;
        if (booting || Apps.prefs(this).getBoolean("on_top", false)) main.postDelayed(() -> show(this), 1500);
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        // Every key passes through here (Android waits for the answer), so this stays trivial.
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Log.i(TAG, "Home key caught");
            main.post(() -> show(this));
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (stock == null) stock = Apps.stockHome(this);
        if (stock == null) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !stock.getPackageName().contentEquals(pkg)) return;
        // Only the home screen itself: the TV launcher's other screens (search, apps) stay usable.
        CharSequence cls = e.getClassName();
        if (cls != null && cls.toString().contains(".") && !stock.getClassName().contentEquals(cls)) {
            Log.d(TAG, "ignored window event from TV home: " + cls);
            return;
        }
        if (Guard.stockAllowed()) return;
        Log.i(TAG, "TV home appeared, replacing it");
        show(this);
    }

    /**
     * Starts QuietTV as a home task: ACTION_MAIN with only CATEGORY_HOME and no data. Android then
     * treats it as a home screen, keeps it above the TV's own, and returns to it when apps close.
     */
    static void show(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastShown < 300) return;
        lastShown = now;
        try {
            c.startActivity(homeIntent(c));
        } catch (RuntimeException ignored) {
        }
    }

    static Intent homeIntent(Context c) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    @Override public void onInterrupt() {}
}
