package com.quiettv.home;

import android.os.SystemClock;

/** Shared state between the home screen and the Home-button guard (same process). */
final class Guard {
    /** Until this time, the TV's own home screen may open without being replaced. */
    private static volatile long allowStockUntil;

    private Guard() {}

    /**
     * The user opened the original home screen on purpose. It's allowed to open now and stays until
     * it is opened again (Home pressed, or coming back from an app), which shows QuietTV as usual.
     */
    static void allowStockHome() {
        allowStockUntil = SystemClock.elapsedRealtime() + 5000;
    }

    static boolean stockAllowed() {
        return SystemClock.elapsedRealtime() < allowStockUntil;
    }

    /** QuietTV is showing, so any earlier permission is used up. */
    static void homeShown() {
        allowStockUntil = 0;
    }
}
