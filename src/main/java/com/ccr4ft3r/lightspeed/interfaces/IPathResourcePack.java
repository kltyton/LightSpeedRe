package com.ccr4ft3r.lightspeed.interfaces;

import net.minecraft.server.packs.PackResources;
import net.neoforged.neoforgespi.locating.IModFile;

public interface IPathResourcePack extends PackResources, IPackResources {
    void lightspeed$setModFile(IModFile modFile);

    void lightspeed$startAsyncPreload();
}
