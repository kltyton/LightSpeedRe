package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.resource.SharedZipIndexHolder;
import com.ccr4ft3r.lightspeed.cache.resource.ZipResourceIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.zip.ZipFile;

/** Shares a single {@link ZipResourceIndex} across all overlay packs backed by the same ZIP access. */
@Mixin(targets = "net.minecraft.server.packs.FilePackResources$SharedZipFileAccess")
public abstract class SharedZipFileAccessIndexMixin implements SharedZipIndexHolder {
    @Unique private volatile ZipResourceIndex lightspeed$sharedIndex;

    @Override
    public ZipResourceIndex lightspeed$getOrCreateZipIndex(ZipFile zip) {
        ZipResourceIndex current = lightspeed$sharedIndex;
        if (current == null) {
            synchronized (this) {
                current = lightspeed$sharedIndex;
                if (current == null) {
                    current = new ZipResourceIndex(zip);
                    lightspeed$sharedIndex = current;
                }
            }
        }
        return current;
    }

    @Override
    public ZipResourceIndex lightspeed$getLoadedZipIndex() {
        return lightspeed$sharedIndex;
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void lightspeed$clearSharedIndex(CallbackInfo ci) {
        lightspeed$sharedIndex = null;
    }
}
