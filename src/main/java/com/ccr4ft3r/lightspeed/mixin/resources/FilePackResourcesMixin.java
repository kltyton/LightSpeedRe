package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.google.common.collect.Maps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Mixin(FilePackResources.class)
public abstract class FilePackResourcesMixin implements IPackResources {
    @Shadow @Final private FilePackResources.SharedZipFileAccess zipFileAccess;
    @Shadow @Final private String prefix;

    @Unique
    private final Map<PackType, List<String>> lightspeed$entriesByPackType = Maps.newConcurrentMap();

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(PackLocationInfo location, FilePackResources.SharedZipFileAccess zipFileAccess, String prefix, CallbackInfo ci) {
        if (GlobalCache.isEnabled) {
            GlobalCache.add(this);
        }
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    public void listResourcesHeadInjected(PackType packType, String namespace, String path, PackResources.ResourceOutput resourceOutput, CallbackInfo ci) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this)) {
            return;
        }

        ZipFile zip = lightspeed$getOpenZipFile();
        if (zip == null) {
            return;
        }

        String namespaceRoot = lightspeed$addPrefix(packType.getDirectory() + "/" + namespace + "/");
        String requestedRoot = namespaceRoot + path + "/";

        List<String> entries = lightspeed$entriesByPackType.get(packType);
        if (entries == null) {
            entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .collect(Collectors.toList());
            lightspeed$entriesByPackType.put(packType, entries);
        }

        entries.stream()
                .filter(entry -> entry.startsWith(requestedRoot))
                .forEach(entry -> {
                    String resourcePath = entry.substring(namespaceRoot.length());
                    ResourceLocation location = ResourceLocation.tryBuild(namespace, resourcePath);
                    if (location != null) {
                        resourceOutput.accept(location, lightspeed$openResource(packType, location));
                    }
                });

        ci.cancel();
    }

    @Unique
    private ZipFile lightspeed$getOpenZipFile() {
        ZipFile zip = ((SharedZipFileAccessAccessor) this.zipFileAccess).lightspeed$getOrCreateZipFile();
        if (zip == null || lightspeed$isOpen(zip)) {
            return zip;
        }

        this.zipFileAccess.close();
        zip = ((SharedZipFileAccessAccessor) this.zipFileAccess).lightspeed$getOrCreateZipFile();
        return zip != null && lightspeed$isOpen(zip) ? zip : null;
    }

    @Unique
    private static boolean lightspeed$isOpen(ZipFile zip) {
        try {
            zip.size();
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    @Unique
    private IoSupplier<InputStream> lightspeed$openResource(PackType packType, ResourceLocation location) {
        return () -> {
            ZipFile zip = lightspeed$getOpenZipFile();
            if (zip == null) {
                throw new FileNotFoundException(location.toString());
            }

            String entryName = lightspeed$addPrefix(packType.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath());
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new FileNotFoundException(location.toString());
            }
            return zip.getInputStream(entry);
        };
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        lightspeed$entriesByPackType.clear();
    }

    @Unique
    private String lightspeed$addPrefix(String path) {
        return this.prefix.isEmpty() ? path : this.prefix + "/" + path;
    }
}
