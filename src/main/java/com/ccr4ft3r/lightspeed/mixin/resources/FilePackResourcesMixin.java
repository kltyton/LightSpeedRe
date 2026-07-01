package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.google.common.collect.Maps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Mixin(FilePackResources.class)
public abstract class FilePackResourcesMixin implements IPackResources {
    @Shadow
    @Nullable
    protected abstract ZipFile getOrCreateZipFile();
    @Shadow
    @Nullable
    private ZipFile zipFile;
    @Unique
    private final Map<PackType, List<String>> lightspeed$entriesByPackType = Maps.newConcurrentMap();

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String name, File file, boolean builtin, CallbackInfo ci) {
        if (GlobalCache.isEnabled)
            GlobalCache.add(this);
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    public void listResourcesHeadInjected(PackType packType, String namespace, String path, PackResources.ResourceOutput resourceOutput, CallbackInfo ci) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this))
            return;

        ZipFile zip = lightspeed$getOpenZipFile();
        if (zip == null) {
            return;
        }

        String s = packType.getDirectory() + "/" + namespace + "/";
        String s1 = s + path + "/";

        List<String> entries;

        if ((entries = lightspeed$entriesByPackType.get(packType)) == null) {
            entries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .map(ZipEntry::getName)
                    .collect(Collectors.toList());
            lightspeed$entriesByPackType.put(packType, entries);
        }

        entries.stream()
                .filter(entry -> entry.startsWith(s1))
                .forEach(entry -> {
                    String s3 = entry.substring(s.length());
                    ResourceLocation resourcelocation = ResourceLocation.tryBuild(namespace, s3);
                    if (resourcelocation != null) {
                        resourceOutput.accept(resourcelocation, lightspeed$openResource(packType, resourcelocation));
                    }
                });

        ci.cancel();
    }

    @Unique
    private ZipFile lightspeed$getOpenZipFile() {
        ZipFile zip = this.getOrCreateZipFile();
        if (zip == null || lightspeed$isOpen(zip)) {
            return zip;
        }

        this.zipFile = null;
        zip = this.getOrCreateZipFile();
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
            String entryName = packType.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath();
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
}
