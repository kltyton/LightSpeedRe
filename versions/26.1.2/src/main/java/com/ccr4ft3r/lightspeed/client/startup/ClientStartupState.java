package com.ccr4ft3r.lightspeed.client.startup;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.Locale;

/** Observes the native GUI owner after successful resource and loader completion. */
public final class ClientStartupState {
    private ClientStartupState() {
    }

    public static boolean titleReady(Minecraft client) {
        return client.screen instanceof TitleScreen
                && StartupMetrics.hasRecorded("client-initialization-complete")
                && StartupMetrics.isInitialReloadComplete();
    }

    public static boolean overlayClear(Minecraft client) {
        return client.getOverlay() == null;
    }

    public static void removeOverlay(Minecraft client) {
        client.setOverlay(null);
    }

    public static boolean isCurrentOverlay(Minecraft client, Object overlay) {
        return client.getOverlay() == overlay;
    }

    public static void finish(Minecraft client) {
        if (StartupMetrics.hasRecorded("title-screen-operable") || !titleReady(client) || !overlayClear(client)) {
            return;
        }
        StartupMetrics.mark("title-screen-init");
        GlobalCache.finishStartupCaches();
        StartupMetrics.mark("title-screen-operable");
        LogUtils.getLogger().info("Lightspeed: Launch took {}s (JVM start to title-screen-operable)",
                String.format(Locale.ROOT, "%.3f", StartupMetrics.completedStartupMillis() / 1000.0));
    }
}
