package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathIndex;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;

@Mixin(FilePackResources.class)
public abstract class FilePackResourcesMixin implements IPackResources {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private File file;
    @Shadow
    @Nullable
    protected abstract ZipFile getOrCreateZipFile();
    @Shadow
    @Nullable
    private ZipFile zipFile;
    @Unique
    private volatile ResourcePathIndex lightspeed$entries;
    @Unique
    private Map<PackType, Set<String>> lightspeed$namespaces = Map.of();

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

        String namespacePrefix = packType.getDirectory() + "/" + namespace + "/";
        lightspeed$getEntries(zip).forEachUnder(namespacePrefix + path, entry -> {
                    String resourcePath = entry.substring(namespacePrefix.length());
                    ResourceLocation resourcelocation = ResourceLocation.tryBuild(namespace, resourcePath);
                    if (resourcelocation != null) {
                        resourceOutput.accept(resourcelocation, lightspeed$openResource(packType, resourcelocation));
                    }
                });

        ci.cancel();
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    public void getNamespacesHeadInjected(PackType packType, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this)) {
            return;
        }
        ZipFile zip = lightspeed$getOpenZipFile();
        if (zip == null) {
            return;
        }
        lightspeed$getEntries(zip);
        cir.setReturnValue(lightspeed$namespaces.getOrDefault(packType, Set.of()));
    }

    @Inject(method = "close", at = @At("HEAD"))
    public void closeHeadInjected(CallbackInfo ci) {
        lightspeed$clearIndex();
    }

    @Unique
    private ResourcePathIndex lightspeed$getEntries(ZipFile zip) {
        ResourcePathIndex current = lightspeed$entries;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = lightspeed$entries;
            if (current == null) {
                List<String> resourcePaths = new ArrayList<>(zip.size());
                Map<PackType, Set<String>> namespaces = new EnumMap<>(PackType.class);
                for (PackType packType : PackType.values()) {
                    namespaces.put(packType, new HashSet<>());
                }
                zip.stream().forEach(entry -> lightspeed$indexEntry(entry, resourcePaths, namespaces));
                Map<PackType, Set<String>> immutableNamespaces = new EnumMap<>(PackType.class);
                namespaces.forEach((packType, values) -> immutableNamespaces.put(packType, Set.copyOf(values)));
                lightspeed$namespaces = Map.copyOf(immutableNamespaces);
                current = ResourcePathIndex.from(resourcePaths);
                lightspeed$entries = current;
            }
            return current;
        }
    }

    @Unique
    private void lightspeed$indexEntry(ZipEntry entry, List<String> resourcePaths,
                                       Map<PackType, Set<String>> namespaces) {
        String name = entry.getName();
        for (PackType packType : PackType.values()) {
            String prefix = packType.getDirectory() + "/";
            if (!name.startsWith(prefix)) {
                continue;
            }
            int namespaceEnd = name.indexOf('/', prefix.length());
            if (namespaceEnd <= prefix.length()) {
                continue;
            }
            String namespace = name.substring(prefix.length(), namespaceEnd);
            if (namespace.equals(namespace.toLowerCase(Locale.ROOT))) {
                namespaces.get(packType).add(namespace);
            } else {
                LOGGER.warn("Ignored non-lowercase namespace: {} in {}", namespace, file);
            }
        }
        if (!entry.isDirectory()) {
            resourcePaths.add(name);
        }
    }

    @Unique
    private void lightspeed$clearIndex() {
        lightspeed$entries = null;
        lightspeed$namespaces = Map.of();
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
        lightspeed$clearIndex();
    }
}
