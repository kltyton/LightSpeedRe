package com.ccr4ft3r.lightspeed.events;

import com.ccr4ft3r.lightspeed.ModConstants;
import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.internal.BrandingControl;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT)
public class TitleScreenInjector {

    private static boolean launchComplete = false;

    @SuppressWarnings({"InstantiationOfUtilityClass", "unchecked"})
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen) || launchComplete)
            return;
        launchComplete = true;
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
        GlobalCache.EXECUTOR.execute(() -> {
            try {
                GlobalCache.disablePersistAndClear();
            } finally {
                GlobalCache.EXECUTOR.shutdown();
            }
        });
    }
}
