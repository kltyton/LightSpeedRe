package com.ccr4ft3r.lightspeed.events;

import com.ccr4ft3r.lightspeed.ModConstants;
import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.client.advice.StartupAdvice;
import com.ccr4ft3r.lightspeed.client.screen.StartupAdviceScreen;
import com.ccr4ft3r.lightspeed.client.startup.ImmediateOverlayClose;
import com.ccr4ft3r.lightspeed.startup.compat.EarlyModPackIndexEngine;
import com.ccr4ft3r.lightspeed.startup.installation.BootstrapAgentInstaller;
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
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && !StartupMetrics.isFramePacingEnabled()) {
            StartupMetrics.enableWorldFramePacing();
            minecraft.getWindow().updateVsync(minecraft.options.enableVsync().get());
        }
        if (startupAdviceHandled) {
            return;
        }
        if (!(minecraft.screen instanceof TitleScreen titleScreen) || !loadingComplete()) {
            return;
        }

        if (!launchComplete) {
            finishTitleInit();
        }
        if (minecraft.getOverlay() instanceof ImmediateOverlayClose overlayClose) {
            overlayClose.lightspeed$closeIfReady();
        }
        if (minecraft.getOverlay() != null) {
            return;
        }

        startupAdviceHandled = true;
        StartupMetrics.mark("title-screen-operable");
        minecraft.getWindow().updateVsync(minecraft.options.enableVsync().get());
        LogUtils.getLogger().info("Lightspeed ObjectHolder routing: calls={} skipped={} elapsedMillis={}",
                ObjectHolderDispatch.calls(), ObjectHolderDispatch.skipped(), ObjectHolderDispatch.elapsedMillis());
        String seconds = String.format(Locale.ROOT, "%.3f", StartupMetrics.completedStartupMillis() / 1000.0);
        launchTime = Component.translatable("lightspeed.startup_time", seconds);
        BrandingControl.forEachLine(true, false, (index, line) -> brandingLines = index + 1);
        LogUtils.getLogger().info("Lightspeed: Launch took {}s (JVM start to title-screen-operable)", seconds);
        ClientSnapshotCoordinator.persistAndLog();
        GlobalCache.persistCacheAsync("bootstrap startup image", BootstrapAgentBridge::persistResourceImage);
        GlobalCache.finishStartupCaches();
        EarlyModPackIndexEngine.flushCacheAsync();
        if (LightspeedConfig.COMMON.installBootstrapAgent.get()) {
            BootstrapAgentInstaller.startPackCompilerAfterTitle();
        }
        boolean installEnabled = LightspeedConfig.COMMON.installBootstrapAgent.get();
        if (StartupAdvice.shouldShowPrompt(
                Runtime.version().feature(),
                System.getProperty(BootstrapAgentInstaller.ACTIVE_PROPERTY),
                System.getProperty(BootstrapAgentInstaller.DYNAMIC_PROPERTY),
                installEnabled,
                BootstrapAgentInstaller.manualConfigurationRequired(),
                BootstrapAgentInstaller.launcherReadyForNextLaunch(),
                LightspeedConfig.COMMON.suppressStartupRecommendations.get())) {
            boolean agentPremainActive = "true".equalsIgnoreCase(System.getProperty(BootstrapAgentInstaller.ACTIVE_PROPERTY))
                    && !"true".equalsIgnoreCase(System.getProperty(BootstrapAgentInstaller.DYNAMIC_PROPERTY));
            minecraft.setScreen(new StartupAdviceScreen(
                    titleScreen,
                    Runtime.version().feature(),
                    !agentPremainActive,
                    installEnabled,
                    BootstrapAgentInstaller.manualConfigurationRequired()));
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (LightspeedConfig.COMMON.showStartupTime.get() && launchTime != null
                && event.getScreen() instanceof TitleScreen && minecraft.getOverlay() == null) {
            event.getGuiGraphics().drawString(minecraft.font, launchTime, 2,
                    event.getScreen().height - 10 - brandingLines * 10, 0xFFFFFFFF);
        }
    }
}
