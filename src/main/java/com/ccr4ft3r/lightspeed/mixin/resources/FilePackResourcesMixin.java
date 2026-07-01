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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
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
    private final Map<PackType, List<ZipEntry>> lightspeed$entriesByPackType = Maps.newConcurrentMap();

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(PackLocationInfo location, FilePackResources.SharedZipFileAccess zipFileAccess, String prefix, CallbackInfo ci) {
        if (GlobalCache.isEnabled)
            GlobalCache.add(this);
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    public void listResourcesHeadInjected(PackType packType, String namespace, String path, PackResources.ResourceOutput resourceOutput, CallbackInfo ci) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this))
            return;

        ZipFile zip = ((SharedZipFileAccessAccessor) this.zipFileAccess).lightspeed$getOrCreateZipFile();
        if (zip == null) {
            return;
        }

        String s = lightspeed$addPrefix(packType.getDirectory() + "/" + namespace + "/");
        String s1 = s + path + "/";

        List<ZipEntry> entries;

        if ((entries = lightspeed$entriesByPackType.get(packType)) == null) {
            entries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .collect(Collectors.toList());
            lightspeed$entriesByPackType.put(packType, entries);
        }

        entries.stream()
                .filter(e -> e.getName().startsWith(s1))
                .forEach(entry -> {
                    String s3 = entry.getName().substring(s.length());
                    ResourceLocation resourcelocation = ResourceLocation.tryBuild(namespace, s3);
                    if (resourcelocation != null) {
                        resourceOutput.accept(resourcelocation, IoSupplier.create(zip, entry));
                    }
                });

        ci.cancel();
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
