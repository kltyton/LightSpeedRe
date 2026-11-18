package com.ccr4ft3r.lightspeed;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.List;

/** Applies startup preferences through the native NeoForge configuration lifecycle. */
@Mod(ModConstants.MOD_ID)
public final class Main {
    public Main(IEventBus modBus, ModContainer container) {
        StartupMetrics.mark("mod-construction");
        modBus.addListener(this::onConfig);
        container.registerConfig(ModConfig.Type.COMMON, LightspeedConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    private void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() != LightspeedConfig.SPEC) {
            return;
        }
        GlobalCache.shouldAsyncPreloadPacks = false;
        GlobalCache.shouldUseDedicatedResourceReloadExecutor =
                LightspeedConfig.COMMON.dedicatedResourceReloadExecutor.get();
        GlobalCache.shouldParallelizeResourcePackLookup = LightspeedConfig.COMMON.parallelResourceLookup.get();
        GlobalCache.shouldCacheResourceExistence = LightspeedConfig.COMMON.cacheResourceExistence.get();
        GlobalCache.shouldVerifyJarHash = LightspeedConfig.COMMON.verifyJarHash.get();
        List<String> patterns = LightspeedConfig.COMMON.isolatedResourceReloadListenerPatterns.get()
                .stream().map(String::valueOf).toList();
        GlobalCache.isolatedResourceReloadListenerPatterns = patterns;
        GlobalCache.shouldIsolateModdedResourceReloadFailures =
                LightspeedConfig.COMMON.isolateModdedResourceReloadFailures.get();
    }

    private void onServerStarted(ServerStartedEvent event) {
        StartupMetrics.mark("server-started");
        if (GlobalCache.isEnabled) {
            GlobalCache.finishStartupCaches();
        }
    }
}
