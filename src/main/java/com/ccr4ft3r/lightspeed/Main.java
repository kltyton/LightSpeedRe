package com.ccr4ft3r.lightspeed;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.List;

@Mod(ModConstants.MOD_ID)
public class Main {

    public Main() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onConfigEvent);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, LightspeedConfig.SPEC);
        updateCacheFlags();
    }

    private void onConfigEvent(ModConfigEvent event) {
        if (event.getConfig().getSpec() == LightspeedConfig.SPEC) {
            updateCacheFlags();
        }
    }

    private void updateCacheFlags() {
        try {
            GlobalCache.shouldAsyncPreloadPacks = LightspeedConfig.COMMON.asyncPreloadPacks.get();
            GlobalCache.shouldParallelizeResourcePackLookup = LightspeedConfig.COMMON.parallelResourceLookup.get();
            GlobalCache.parallelLookupMinPacks = LightspeedConfig.COMMON.parallelLookupMinPacks.get();
            GlobalCache.shouldCacheResourceExistence = LightspeedConfig.COMMON.cacheResourceExistence.get();
            GlobalCache.shouldIsolateModdedResourceReloadFailures = LightspeedConfig.COMMON.isolateModdedResourceReloadFailures.get();
            GlobalCache.isolatedResourceReloadListenerPatterns = LightspeedConfig.COMMON.isolatedResourceReloadListenerPatterns.get()
                    .stream()
                    .map(String::valueOf)
                    .toList();
        } catch (IllegalStateException ignored) {
            // Forge has not attached the TOML yet; keep the default-on startup flags.
            GlobalCache.isolatedResourceReloadListenerPatterns = List.of("*");
        }

        if (ModList.get().isLoaded(ModConstants.SOPHISTICATED_STORAGE_ID) && ModList.get().isLoaded(ModConstants.JSON_THINGS_ID)) {
            GlobalCache.shouldCacheWalkedPaths = false;
        }
        if (ModList.get().isLoaded(ModConstants.MULTIBLOCKED_ID)) {
            GlobalCache.shouldCacheMaterials = false;
        }
    }
}
