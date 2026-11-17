package com.ccr4ft3r.lightspeed.interfaces;

import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathIndex;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.forgespi.locating.IModFile;

public interface IPathResourcePack extends PackResources, IPackResources {
    void lightspeed$setModFile(IModFile modFile);

    void lightspeed$startAsyncPreload();

    Boolean lightspeed$hasIndexedResource(PackType type, ResourceLocation location);

    default ResourcePathIndex lightspeed$earlyPathIndex(PackType type, String namespace) {
        return null;
    }
}
