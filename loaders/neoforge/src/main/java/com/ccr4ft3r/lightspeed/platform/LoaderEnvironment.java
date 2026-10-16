package com.ccr4ft3r.lightspeed.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Path;

/** Loader-owned paths and optional-mod discovery for shared resource caches. */
public final class LoaderEnvironment {
    public static final boolean CLEAN_LEGACY_CACHES = false;
    private LoaderEnvironment() {}
    public static Path gameDirectory() { return FMLPaths.GAMEDIR.get(); }
    public static String gameVersion() { return net.minecraft.SharedConstants.getCurrentVersion().getId(); }
    public static boolean isModLoaded(String id) { return ModList.get().isLoaded(id); }
}
