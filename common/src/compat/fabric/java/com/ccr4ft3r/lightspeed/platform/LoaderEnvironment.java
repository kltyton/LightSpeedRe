package com.ccr4ft3r.lightspeed.platform;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** Fabric-owned paths and metadata for startup caches. */
public final class LoaderEnvironment {
    public static final boolean CLEAN_LEGACY_CACHES = false;

    private LoaderEnvironment() {
    }

    public static StartupCachePlatform.Profile startupProfile() {
        return StartupCachePlatform.Profile.FABRIC;
    }

    public static Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static String gameVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
    }

    public static boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }
}
