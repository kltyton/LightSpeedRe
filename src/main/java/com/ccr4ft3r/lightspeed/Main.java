package com.ccr4ft3r.lightspeed;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
import com.ccr4ft3r.lightspeed.startup.installation.NativeLaunchProfileCleanup;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.List;

@Mod(ModConstants.MOD_ID)
public class Main {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean loggedConnectorCompatibilityMode;

    @SuppressWarnings("removal")
    public Main() {
        StartupMetrics.mark("mod-construction");
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onConfigEvent);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, LightspeedConfig.SPEC);
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
            GlobalCache.shouldAsyncPreloadPacks = LightspeedConfig.COMMON.asyncPreloadPacks.get();
            GlobalCache.shouldUseDedicatedResourceReloadExecutor = LightspeedConfig.COMMON.dedicatedResourceReloadExecutor.get();
            GlobalCache.shouldParallelizeResourcePackLookup = LightspeedConfig.COMMON.parallelResourceLookup.get();
            GlobalCache.shouldCacheResourceExistence = LightspeedConfig.COMMON.cacheResourceExistence.get();
            GlobalCache.shouldVerifyJarHash = LightspeedConfig.COMMON.verifyJarHash.get();
            GlobalCache.shouldIsolateModdedResourceReloadFailures = LightspeedConfig.COMMON.isolateModdedResourceReloadFailures.get();
            GlobalCache.shouldUseConnectorCompatibilityMode = LightspeedConfig.COMMON.connectorCompatibilityMode.get();
            GlobalCache.isolatedResourceReloadListenerPatterns = LightspeedConfig.COMMON.isolatedResourceReloadListenerPatterns.get()
                    .stream()
                    .map(String::valueOf)
                    .toList();
            if (GlobalCache.isolatedResourceReloadListenerPatterns.contains("*")) {
                GlobalCache.shouldIsolateModdedResourceReloadFailures = false;
                GlobalCache.isolatedResourceReloadListenerPatterns = List.of();
                LOGGER.warn("Lightspeed no longer accepts wildcard resource-reload failure isolation; configure explicit listener class prefixes to opt in");
            }
        } catch (IllegalStateException ignored) {
            // Forge has not attached the TOML yet; failure isolation remains opt-in.
            GlobalCache.shouldIsolateModdedResourceReloadFailures = false;
            GlobalCache.isolatedResourceReloadListenerPatterns = List.of();
        }

        if (GlobalCache.shouldUseConnectorCompatibilityMode && ModList.get().isLoaded(ModConstants.CONNECTOR_ID)) {
            GlobalCache.shouldAsyncPreloadPacks = false;
            GlobalCache.shouldUseDedicatedResourceReloadExecutor = false;
            GlobalCache.shouldParallelizeResourcePackLookup = false;
            GlobalCache.shouldCacheWalkedPaths = false;
            GlobalCache.shouldCacheEmptyNamespaces = false;
            GlobalCache.shouldCacheResourceExistence = false;
            GlobalCache.shouldIsolateModdedResourceReloadFailures = false;
            if (!loggedConnectorCompatibilityMode) {
                LOGGER.warn("Lightspeed Sinytra Connector compatibility mode is active; resource-pack parallelism, path caches, and reload failure isolation are disabled");
                loggedConnectorCompatibilityMode = true;
            }
        }

        if (ModList.get().isLoaded(ModConstants.SOPHISTICATED_STORAGE_ID) && ModList.get().isLoaded(ModConstants.JSON_THINGS_ID)) {
            GlobalCache.shouldCacheWalkedPaths = false;
        }
        if (ModList.get().isLoaded(ModConstants.MULTIBLOCKED_ID)) {
            GlobalCache.shouldCacheMaterials = false;
        }
    }
}
