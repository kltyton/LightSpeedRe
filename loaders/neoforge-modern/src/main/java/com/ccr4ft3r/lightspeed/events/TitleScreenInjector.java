package com.ccr4ft3r.lightspeed.events;

import com.ccr4ft3r.lightspeed.ModConstants;
import com.ccr4ft3r.lightspeed.client.startup.ClientStartupState;
import com.ccr4ft3r.lightspeed.client.startup.StartupTimeText;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.internal.BrandingControl;

/** Renders the frozen startup duration above native NeoForge branding. */
@EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT)
public final class TitleScreenInjector {
    private static int brandingLines = 1;

    @SubscribeEvent
    public static void onResourcesReady(ClientResourceLoadFinishedEvent event) {
        if (event.isInitial()) {
            StartupMetrics.mark("client-initialization-complete");
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientStartupState.finish(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen) {
            StartupMetrics.mark("title-screen-init");
            BrandingControl.forEachLine(true, false, (index, line) -> brandingLines = index + 1);
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (event.getScreen() instanceof TitleScreen && ClientStartupState.overlayClear(client)
                && StartupTimeText.get() != null) {
            event.getGuiGraphics().text(client.font, StartupTimeText.get(), 2,
                    event.getGuiGraphics().guiHeight() - 10 - brandingLines * 10, 0xFFFFFFFF);
        }
    }
}
