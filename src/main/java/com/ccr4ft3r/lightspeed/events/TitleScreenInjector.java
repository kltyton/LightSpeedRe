package com.ccr4ft3r.lightspeed.events;

import com.ccr4ft3r.lightspeed.ModConstants;
import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.client.advice.StartupAdvice;
import com.ccr4ft3r.lightspeed.client.screen.StartupAdviceScreen;
import com.ccr4ft3r.lightspeed.client.cache.assets.ClientSnapshotCoordinator;
import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
import com.ccr4ft3r.lightspeed.startup.installation.BootstrapAgentInstaller;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.internal.BrandingControl;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT)
public class TitleScreenInjector {

    private static boolean launchComplete = false;
    private static boolean startupAdviceHandled = false;

    @SuppressWarnings({"InstantiationOfUtilityClass", "unchecked"})
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInit(ScreenEvent.Init event) {
        if (!(event.getScreen() instanceof TitleScreen) || launchComplete)
            return;
        launchComplete = true;
        StartupMetrics.mark("title-screen-init");
        try {
            long secondsToStart = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
            LogUtils.getLogger().info("Lightspeed: Launch took {}s", secondsToStart);
            BrandingControl brandingControl = new BrandingControl();

            Field f = BrandingControl.class.getDeclaredField("brandings");
            f.setAccessible(true);
            Method computeBranding = BrandingControl.class.getDeclaredMethod("computeBranding");
            computeBranding.setAccessible(true);
            computeBranding.invoke(null);

            List<String> brandings = new ArrayList<>((List<String>) f.get(brandingControl));
            if (brandings.size() > 1) {
                List<String> newBrandings = new ArrayList<>(brandings);
                f.set(brandingControl, newBrandings);
                newBrandings.add("Lightspeed: Launch took " + secondsToStart + "s");
            }
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException e) {
            LogUtils.getLogger().error("Cannot add launch time to title screen", e);
        }
        ClientSnapshotCoordinator.persistAndLog();
        BootstrapAgentBridge.persistResourceImage();
        BootstrapAgentInstaller.startPackCompilerAfterTitle();
        GlobalCache.beginShutdown();
        GlobalCache.EXECUTOR.execute(() -> {
            try {
                GlobalCache.disablePersistAndClear();
            } finally {
                GlobalCache.shutdownExecutors();
            }
        });
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !launchComplete || startupAdviceHandled) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof TitleScreen titleScreen)) {
            return;
        }

        startupAdviceHandled = true;
        StartupMetrics.mark("title-screen-operable");
        if (LightspeedConfig.COMMON.suppressStartupRecommendations.get()) {
            return;
        }

        boolean manualConfigurationRequired = BootstrapAgentInstaller.manualConfigurationRequired();
        StartupAdvice.Advice advice = StartupAdvice.select(
                Runtime.version().feature(),
                System.getProperty(BootstrapAgentInstaller.ACTIVE_PROPERTY),
                System.getProperty(BootstrapAgentInstaller.DYNAMIC_PROPERTY),
                manualConfigurationRequired,
                BootstrapAgentInstaller.launcherReadyForNextLaunch());
        if (advice.shouldShow()) {
            String manualArguments = advice.manualConfigurationRequired()
                    ? BootstrapAgentInstaller.manualJvmArguments() : "";
            minecraft.setScreen(new StartupAdviceScreen(
                    titleScreen, advice.java21Recommended(), advice.agentMissing(),
                    advice.manualConfigurationRequired(), manualArguments));
        }
    }
}
