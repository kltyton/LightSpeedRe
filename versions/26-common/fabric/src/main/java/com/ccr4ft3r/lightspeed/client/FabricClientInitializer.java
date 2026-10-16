package com.ccr4ft3r.lightspeed.client;

import com.ccr4ft3r.lightspeed.client.startup.ClientStartupState;
import com.ccr4ft3r.lightspeed.client.startup.StartupTimeText;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.TitleScreen;

/** Connects Fabric's lifecycle and screen extraction events to the shared startup clock. */
public final class FabricClientInitializer implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> StartupMetrics.mark("client-initialization-complete"));
        ClientTickEvents.END_CLIENT_TICK.register(ClientStartupState::finish);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                StartupMetrics.mark("title-screen-init");
                ScreenEvents.afterExtract(screen).register((rendered, graphics, mouseX, mouseY, delta) -> {
                    if (ClientStartupState.overlayClear(client) && StartupTimeText.get() != null) {
                        graphics.text(client.font, StartupTimeText.get(), 2, graphics.guiHeight() - 20, 0xFFFFFFFF);
                    }
                });
            }
        });
    }
}
