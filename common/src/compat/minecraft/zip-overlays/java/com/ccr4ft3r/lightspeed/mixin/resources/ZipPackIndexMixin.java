package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.SharedZipIndexHolder;
import com.ccr4ft3r.lightspeed.cache.resource.ZipResourceIndex;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reuses shared archive-name indexes across overlays while leaving filtering and streams to Minecraft. */
@Mixin(FilePackResources.class)
public abstract class ZipPackIndexMixin implements IndexedPackResources {
    @Shadow @Final private String prefix;
    @Unique private volatile SharedZipIndexHolder lightspeed$sharedAccess;
    @Unique private volatile ZipResourceIndex lightspeed$fallbackIndex;

    @WrapOperation(method = "listResources", at = @At(value = "INVOKE",
            target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"))
    private Enumeration<? extends ZipEntry> lightspeed$listDirectory(ZipFile zip,
            Operation<Enumeration<? extends ZipEntry>> original,
            @Local(argsOnly = true) PackType type,
            @Local(argsOnly = true, ordinal = 0) String namespace,
            @Local(argsOnly = true, ordinal = 1) String directory) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this)) {
            return original.call(zip);
        }
        String sub = directory == null || directory.isEmpty()
                ? type.getDirectory() + "/" + namespace + "/"
                : type.getDirectory() + "/" + namespace + "/" + directory + "/";
        Enumeration<? extends ZipEntry> selected = lightspeed$index(zip).entries(zip, lightspeed$prefix(sub));
        return selected == null ? original.call(zip) : selected;
    }

    @WrapOperation(method = "getNamespaces", at = @At(value = "INVOKE",
            target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"))
    private Enumeration<? extends ZipEntry> lightspeed$listNamespaces(ZipFile zip,
            Operation<Enumeration<? extends ZipEntry>> original,
            @Local(argsOnly = true) PackType type) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this)) {
            return original.call(zip);
        }
        Enumeration<? extends ZipEntry> selected = lightspeed$index(zip).entries(zip,
                lightspeed$prefix(type.getDirectory() + "/"));
        return selected == null ? original.call(zip) : selected;
    }

    @WrapOperation(method = {"getNamespaces", "listResources",
            "getResource(Ljava/lang/String;)Lnet/minecraft/server/packs/resources/IoSupplier;"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/FilePackResources$SharedZipFileAccess;getOrCreateZipFile()Ljava/util/zip/ZipFile;"))
    private ZipFile lightspeed$openZip(@Coerce Object access, Operation<ZipFile> original) {
        if (lightspeed$sharedAccess == null && access instanceof SharedZipIndexHolder holder) {
            lightspeed$sharedAccess = holder;
        }
        ZipFile zip = original.call(access);
        if (GlobalCache.isEnabled && zip != null) {
            try {
                zip.size();
            } catch (IllegalStateException closed) {
                SharedZipFileAccessAccessor lifetime = (SharedZipFileAccessAccessor) access;
                lifetime.lightspeed$close();
                return lifetime.lightspeed$getOrCreateZipFile();
            }
        }
        return zip;
    }

    @Unique
    private ZipResourceIndex lightspeed$index(ZipFile zip) {
        SharedZipIndexHolder holder = lightspeed$sharedAccess;
        if (holder != null) {
            return holder.lightspeed$getOrCreateZipIndex(zip);
        }
        ZipResourceIndex current = lightspeed$fallbackIndex;
        if (current == null) {
            synchronized (this) {
                current = lightspeed$fallbackIndex;
                if (current == null) {
                    current = new ZipResourceIndex(zip);
                    lightspeed$fallbackIndex = current;
                }
            }
        }
        return current;
    }

    @Unique
    private ZipResourceIndex lightspeed$loadedIndex() {
        SharedZipIndexHolder holder = lightspeed$sharedAccess;
        return holder != null ? holder.lightspeed$getLoadedZipIndex() : lightspeed$fallbackIndex;
    }

    @Unique
    private String lightspeed$prefix(String path) {
        return prefix.isEmpty() ? path : prefix + "/" + path;
    }

    @Override
    public Boolean lightspeed$containsIndexed(String type, String namespace, String path) {
        if (((Object) this).getClass() != FilePackResources.class) {
            return null;
        }
        ZipResourceIndex index = lightspeed$loadedIndex();
        return index == null ? null : index.contains(lightspeed$prefix(type + "/" + namespace + "/" + path));
    }

    @Override
    public Boolean lightspeed$hasAnyUnder(PackType type, String namespace, String path) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths
                || ((Object) this).getClass() != FilePackResources.class || FusionPackCompat.hasOverrides(this)) {
            return null;
        }
        ZipResourceIndex index = lightspeed$loadedIndex();
        if (index == null) {
            return null;
        }
        String sub = path == null || path.isEmpty()
                ? type.getDirectory() + "/" + namespace + "/"
                : type.getDirectory() + "/" + namespace + "/" + path;
        return index.hasAnyUnder(lightspeed$prefix(sub));
    }
}
