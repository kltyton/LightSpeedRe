package com.ccr4ft3r.lightspeed.mixin.resources;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.zip.ZipFile;

/** Accesses the native ZIP lifetime without exposing its private implementation type. */
@Mixin(targets = "net.minecraft.server.packs.FilePackResources$SharedZipFileAccess")
public interface SharedZipFileAccessAccessor {
    @Invoker("getOrCreateZipFile")
    ZipFile lightspeed$getOrCreateZipFile();

    @Invoker("close")
    void lightspeed$close();
}
