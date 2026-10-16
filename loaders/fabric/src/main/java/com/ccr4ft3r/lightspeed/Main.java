package com.ccr4ft3r.lightspeed;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.config.FabricLightspeedConfig;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Initializes Fabric's startup cache preferences before client resource preparation. */
public final class Main implements ModInitializer {
    @Override
    public void onInitialize() {
        StartupMetrics.mark("mod-construction");
        FabricLightspeedConfig.load();
        GlobalCache.loadPersistedCachesAsync();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            StartupMetrics.mark("server-started");
            if (GlobalCache.isEnabled) {
                GlobalCache.finishStartupCaches();
            }
        });
    }
}
