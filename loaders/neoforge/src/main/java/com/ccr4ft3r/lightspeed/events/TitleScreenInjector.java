package com.ccr4ft3r.lightspeed.events;

import com.ccr4ft3r.lightspeed.ModConstants;
import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.client.loading.ClientModLoader;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.internal.BrandingControl;

import java.util.Locale;

@EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT)
public class TitleScreenInjector {

    private static boolean launchComplete = false;
    private static Component launchTime;
    private static int brandingLines;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen) || launchComplete || !ready())
            return;
        finishTitleInit();
    }

    private static boolean ready() {
        return !ClientModLoader.isLoading() && !ModLoader.hasErrors()
                && StartupMetrics.isInitialReloadComplete();
    }

    private static void finishTitleInit() {
        launchComplete = true;
        StartupMetrics.mark("title-screen-init");
        BootstrapAgentBridge.persistResourceImage();
        GlobalCache.finishStartupCaches();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof TitleScreen) || !ready()) {
            return;
        }
        if (!launchComplete && ready()) {
            finishTitleInit();
        }
        if (launchComplete && minecraft.getOverlay() == null && StartupMetrics.mark("title-screen-operable")) {
            String seconds = String.format(Locale.ROOT, "%.3f", StartupMetrics.completedStartupMillis() / 1000.0);
            launchTime = Component.translatable("lightspeed.startup_time", seconds);
            BrandingControl.forEachLine(true, false, (index, line) -> brandingLines = index + 1);
            LogUtils.getLogger().info("Lightspeed: Launch took {}s (JVM start to title-screen-operable)", seconds);
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (launchTime != null && event.getScreen() instanceof TitleScreen && minecraft.getOverlay() == null) {
            event.getGuiGraphics().drawString(minecraft.font, launchTime, 2,
                    event.getScreen().height - 10 - brandingLines * 10, 0xFFFFFFFF);
        }
    }
}
