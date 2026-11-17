package com.ccr4ft3r.lightspeed.startup.loading;

import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.forgespi.locating.IModFile;

import java.util.List;

/** Bridge implemented on {@code ModLoader} by bytecode transformation so {@code buildMods} can run in parallel. */
public interface ModLoaderBridge {
    List<ModContainer> lightspeed$buildMods(IModFile modFile);
}
