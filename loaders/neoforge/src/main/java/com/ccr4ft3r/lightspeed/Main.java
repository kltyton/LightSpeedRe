package com.ccr4ft3r.lightspeed;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
import com.ccr4ft3r.lightspeed.startup.installation.NativeLaunchProfileCleanup;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.util.List;

@Mod(ModConstants.MOD_ID)
public class Main {
    private static final Logger LOGGER = LogUtils.getLogger();

    public Main(IEventBus modEventBus, ModContainer modContainer) {
        StartupMetrics.mark("mod-construction");
        modEventBus.addListener(this::onConfigEvent);
        modContainer.registerConfig(ModConfig.Type.COMMON, LightspeedConfig.SPEC);
        updateCacheFlags();
    }

    private void onConfigEvent(ModConfigEvent event) {
        if (event.getConfig().getSpec() == LightspeedConfig.SPEC) {
            updateCacheFlags();
            if (event instanceof ModConfigEvent.Loading && FMLEnvironment.dist.isClient()) {
                NativeLaunchProfileCleanup.removeBootstrapArguments(FMLPaths.GAMEDIR.get());
            }
        }
    }

    private void updateCacheFlags() {
        GlobalCache.shouldCacheWalkedPaths = true;
        GlobalCache.shouldCacheEmptyNamespaces = true;
        GlobalCache.shouldCacheMaterials = true;

        try {
            GlobalCache.shouldUseDedicatedResourceReloadExecutor = LightspeedConfig.COMMON.dedicatedResourceReloadExecutor.get();
            GlobalCache.shouldParallelizeResourcePackLookup = LightspeedConfig.COMMON.parallelResourceLookup.get();
            GlobalCache.shouldCacheResourceExistence = LightspeedConfig.COMMON.cacheResourceExistence.get();
            GlobalCache.shouldVerifyJarHash = LightspeedConfig.COMMON.verifyJarHash.get();
            GlobalCache.shouldIsolateModdedResourceReloadFailures = LightspeedConfig.COMMON.isolateModdedResourceReloadFailures.get();
            GlobalCache.isolatedResourceReloadListenerPatterns = LightspeedConfig.COMMON.isolatedResourceReloadListenerPatterns.get()
                    .stream()
                    .map(String::valueOf)
                    .toList();
            if (GlobalCache.isolatedResourceReloadListenerPatterns.contains("*")) {
                GlobalCache.shouldIsolateModdedResourceReloadFailures = false;
                GlobalCache.isolatedResourceReloadListenerPatterns = List.of();
                LOGGER.warn("Lightspeed requires explicit listener class prefixes for resource-reload failure isolation");
            }
        } catch (IllegalStateException ignored) {
            // NeoForge has not attached the TOML yet; failure isolation remains opt-in.
            GlobalCache.shouldIsolateModdedResourceReloadFailures = false;
            GlobalCache.isolatedResourceReloadListenerPatterns = List.of();
        }

        if (ModList.get().isLoaded(ModConstants.SOPHISTICATED_STORAGE_ID) && ModList.get().isLoaded(ModConstants.JSON_THINGS_ID)) {
            GlobalCache.shouldCacheWalkedPaths = false;
        }
        if (ModList.get().isLoaded(ModConstants.MULTIBLOCKED_ID)) {
            GlobalCache.shouldCacheMaterials = false;
        }
    }

}
