package com.ccr4ft3r.lightspeed.cache.resource;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.InputStream;

/** Converts cached paths through the modern identifier API. */
public final class ResourceOutputEmitter {
    private ResourceOutputEmitter() {
    }

    public static boolean emit(String namespace, String path, IoSupplier<InputStream> stream,
            PackResources.ResourceOutput output) {
        Identifier location = Identifier.tryBuild(namespace, path);
        if (location == null) {
            return false;
        }
        output.accept(location, stream);
        return true;
    }
}
