package com.ccr4ft3r.lightspeed.bootstrap.runtime.filesystem;

/** Tracks the startup archive preopen window without changing third-party network settings. */
public final class StartupArchiveWindow {
    private static boolean started;
    private static boolean restored;

    private StartupArchiveWindow() { }

    public static synchronized void start() {
        started = true;
    }

    public static synchronized void finish() {
        ReadOnlyArchiveOpen.closeUnconsumed();
        if (started) restored = true;
    }

    public static boolean started() { return started; }
    public static boolean restored() { return restored; }
}
