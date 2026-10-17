package com.ccr4ft3r.lightspeed.events;

import com.ccr4ft3r.lightspeed.ModConstants;
import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.client.screen.StartupAdviceScreen;
import com.ccr4ft3r.lightspeed.client.cache.assets.ClientSnapshotCoordinator;
import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.ccr4ft3r.lightspeed.startup.registry.ObjectHolderDispatch;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.loading.ClientModLoader;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.internal.BrandingControl;

import java.util.Locale;

@Mod.EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT)
public class TitleScreenInjector {

    private static boolean launchComplete = false;
    private static boolean startupAdviceHandled = false;
    private static Component launchTime;
    private static int brandingLines;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen) || launchComplete || !loadingComplete())
            return;
        finishTitleInit();
    }

    private static boolean loadingComplete() {
        return !ClientModLoader.isLoading() && ModLoader.isLoadingStateValid()
                && StartupMetrics.isInitialReloadComplete();
    }

    private static void finishTitleInit() {
        launchComplete = true;
        StartupMetrics.mark("title-screen-init");
        ClientSnapshotCoordinator.persistAndLog();
        BootstrapAgentBridge.persistResourceImage();
        GlobalCache.finishStartupCaches();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || startupAdviceHandled) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof TitleScreen titleScreen) || minecraft.getOverlay() != null
                || !loadingComplete()) {
            return;
        }

        if (!launchComplete) {
            finishTitleInit();
        }

        startupAdviceHandled = true;
        StartupMetrics.mark("title-screen-operable");
        LogUtils.getLogger().info("Lightspeed ObjectHolder routing: calls={} skipped={} elapsedMillis={}",
                ObjectHolderDispatch.calls(), ObjectHolderDispatch.skipped(), ObjectHolderDispatch.elapsedMillis());
        String seconds = String.format(Locale.ROOT, "%.3f", StartupMetrics.completedStartupMillis() / 1000.0);
        launchTime = Component.translatable("lightspeed.startup_time", seconds);
        BrandingControl.forEachLine(true, false, (index, line) -> brandingLines = index + 1);
        LogUtils.getLogger().info("Lightspeed: Launch took {}s (JVM start to title-screen-operable)", seconds);
        if (LightspeedConfig.COMMON.suppressStartupRecommendations.get()) {
            return;
        }

        if (Runtime.version().feature() < 21) {
            minecraft.setScreen(new StartupAdviceScreen(
                    titleScreen, true, false, false, ""));
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
