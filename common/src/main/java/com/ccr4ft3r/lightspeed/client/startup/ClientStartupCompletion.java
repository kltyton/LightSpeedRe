package com.ccr4ft3r.lightspeed.client.startup;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.logging.LogUtils;

import java.util.Locale;

/** Completes startup after the native GUI owner has reached an operable title screen. */
public final class ClientStartupCompletion {
    private ClientStartupCompletion() {}

    public static boolean initializationComplete() {
        return StartupMetrics.hasRecorded("client-initialization-complete") && StartupMetrics.isInitialReloadComplete();
    }

    public static boolean finished() {
        return StartupMetrics.hasRecorded("title-screen-operable");
    }

    public static void finish() {
        StartupMetrics.mark("title-screen-init");
        GlobalCache.finishStartupCaches();
        StartupMetrics.mark("title-screen-operable");
        LogUtils.getLogger().info("Lightspeed: Launch took {}s (JVM start to title-screen-operable)",
                String.format(Locale.ROOT, "%.3f", StartupMetrics.completedStartupMillis() / 1000.0));
    }
}
