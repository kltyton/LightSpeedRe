package com.ccr4ft3r.lightspeed;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

import java.util.List;

@Mod(ModConstants.MOD_ID)
public class Main {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean loggedConnectorCompatibilityMode;

    public Main(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::onConfigEvent);
        modContainer.registerConfig(ModConfig.Type.COMMON, LightspeedConfig.SPEC);
        updateCacheFlags();
    }

    private void onConfigEvent(ModConfigEvent event) {
        if (event.getConfig().getSpec() == LightspeedConfig.SPEC) {
            updateCacheFlags();
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
            GlobalCache.parallelLookupMinPacks = LightspeedConfig.COMMON.parallelLookupMinPacks.get();
            GlobalCache.shouldCacheResourceExistence = LightspeedConfig.COMMON.cacheResourceExistence.get();
            GlobalCache.shouldIsolateModdedResourceReloadFailures = LightspeedConfig.COMMON.isolateModdedResourceReloadFailures.get();
            GlobalCache.shouldUseConnectorCompatibilityMode = LightspeedConfig.COMMON.connectorCompatibilityMode.get();
            GlobalCache.isolatedResourceReloadListenerPatterns = LightspeedConfig.COMMON.isolatedResourceReloadListenerPatterns.get()
                    .stream()
                    .map(String::valueOf)
                    .toList();
        } catch (IllegalStateException ignored) {
            // NeoForge has not attached the TOML yet; keep the default-on startup flags.
            GlobalCache.isolatedResourceReloadListenerPatterns = List.of("*");
        }

        if (GlobalCache.shouldUseConnectorCompatibilityMode && needsResourcePackCompatibilityMode()) {
            GlobalCache.shouldAsyncPreloadPacks = false;
            GlobalCache.shouldUseDedicatedResourceReloadExecutor = false;
            GlobalCache.shouldParallelizeResourcePackLookup = false;
            GlobalCache.shouldCacheWalkedPaths = false;
            GlobalCache.shouldCacheEmptyNamespaces = false;
            GlobalCache.shouldCacheResourceExistence = false;
            GlobalCache.shouldIsolateModdedResourceReloadFailures = false;
            if (!loggedConnectorCompatibilityMode) {
                LOGGER.warn("Lightspeed Connector/Continuity compatibility mode is active; resource-pack parallelism, path caches, and reload failure isolation are disabled");
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

    private static boolean needsResourcePackCompatibilityMode() {
        return ModList.get().isLoaded(ModConstants.CONNECTOR_ID)
                || ModList.get().isLoaded(ModConstants.CONNECTOR_LEGACY_ID)
                || ModList.get().isLoaded(ModConstants.CONTINUITY_ID);
    }
}
