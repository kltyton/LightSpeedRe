package com.ccr4ft3r.lightspeed.client;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.client.startup.StartupTimeText;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.Locale;

/** Publishes startup completion once and renders the same frozen duration on subsequent title screens. */
public final class FabricClientInitializer implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> StartupMetrics.mark("mod-loading-complete"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null
                    || !StartupMetrics.hasRecorded("mod-loading-complete")
                    || !StartupMetrics.isInitialReloadComplete()
                    || StartupMetrics.hasRecorded("title-screen-operable")) {
                return;
            }
            StartupMetrics.mark("title-screen-init");
            com.ccr4ft3r.lightspeed.client.cache.assets.ClientSnapshotCoordinator.persistAndLog();
            GlobalCache.finishStartupCaches();
            StartupMetrics.mark("title-screen-operable");
            LogUtils.getLogger().info("Lightspeed: Launch took {}s (JVM start to title-screen-operable)",
                    String.format(Locale.ROOT, "%.3f", StartupMetrics.completedStartupMillis() / 1000.0));
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                StartupMetrics.mark("title-screen-init");
                ScreenEvents.afterRender(screen).register((rendered, graphics, mouseX, mouseY, delta) -> {
                    if (client.getOverlay() == null && StartupTimeText.get() != null) {
                        graphics.drawString(client.font, StartupTimeText.get(), 2,
                                client.getWindow().getGuiScaledHeight() - 20, 0xFFFFFFFF);
                    }
                });
            }
        });
    }
}
