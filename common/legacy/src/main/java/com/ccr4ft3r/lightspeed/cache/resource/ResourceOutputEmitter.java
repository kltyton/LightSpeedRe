package com.ccr4ft3r.lightspeed.cache.resource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.InputStream;

/** Converts a cached path through Minecraft's resource identifier validation. */
public final class ResourceOutputEmitter {
    private ResourceOutputEmitter() {
    }

    public static boolean emit(String namespace, String path, IoSupplier<InputStream> stream,
            PackResources.ResourceOutput output) {
        ResourceLocation location = ResourceLocation.tryBuild(namespace, path);
        if (location == null) {
            return false;
        }
        output.accept(location, stream);
        return true;
    }
}
