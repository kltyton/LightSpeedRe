package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.OrderedZipEntryIndex;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
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
import java.io.InputStream;
import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Mixin(FilePackResources.class)
public abstract class ForgeFilePackResourcesMixin implements IPackResources {
    @Shadow
    @Nullable
    protected abstract ZipFile getOrCreateZipFile();
    @Shadow
    @Nullable
    private ZipFile zipFile;
    @Unique
    private volatile OrderedZipEntryIndex lightspeed$entries;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String name, File file, boolean builtin, CallbackInfo ci) {
        if (GlobalCache.isEnabled)
            GlobalCache.add(this);
    }

    @WrapOperation(method = {"listResources", "getNamespaces"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/FilePackResources;getOrCreateZipFile()Ljava/util/zip/ZipFile;"))
    private ZipFile lightspeed$reopenClosedZip(FilePackResources pack, Operation<ZipFile> original) {
        ZipFile zip = original.call(pack);
        if (!GlobalCache.isEnabled || zip == null || lightspeed$isOpen(zip) || FusionPackCompat.hasOverrides(this)) return zip;
        this.zipFile = null;
        return original.call(pack);
    }

    @WrapOperation(method = "listResources", at = @At(value = "INVOKE",
            target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;", remap = false))
    private Enumeration<? extends ZipEntry> lightspeed$listEntries(ZipFile zip,
            Operation<Enumeration<? extends ZipEntry>> original, PackType type, String namespace,
            String path, PackResources.ResourceOutput output,
            @Share("lightspeed$reopenZip") LocalBooleanRef reopen) {
        Enumeration<? extends ZipEntry> entries = original.call(zip);
        if (!GlobalCache.isEnabled || type == null || !OrderedZipEntryIndex.supports(zip, entries)) return entries;
        String prefix = type.getDirectory() + "/" + namespace + "/" + path + "/";
        Enumeration<? extends ZipEntry> selected = lightspeed$getEntries(zip).select(prefix, entries);
        reopen.set(selected != entries && !FusionPackCompat.hasOverrides(this));
        return selected;
    }

    @WrapOperation(method = "getNamespaces", at = @At(value = "INVOKE",
            target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;", remap = false))
    private Enumeration<? extends ZipEntry> lightspeed$namespaceEntries(ZipFile zip,
            Operation<Enumeration<? extends ZipEntry>> original, PackType type) {
        Enumeration<? extends ZipEntry> entries = original.call(zip);
        if (!GlobalCache.isEnabled || type == null || !OrderedZipEntryIndex.supports(zip, entries)) return entries;
        return lightspeed$getEntries(zip).select(type.getDirectory() + "/", entries);
    }

    @WrapOperation(method = "listResources", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/IoSupplier;create(Ljava/util/zip/ZipFile;Ljava/util/zip/ZipEntry;)Lnet/minecraft/server/packs/resources/IoSupplier;"))
    private IoSupplier<InputStream> lightspeed$reopenResource(ZipFile zip, ZipEntry entry,
            Operation<IoSupplier<InputStream>> original,
            @Share("lightspeed$reopenZip") LocalBooleanRef reopen) {
        IoSupplier<InputStream> supplier = original.call(zip, entry);
        if (!reopen.get() || supplier == null) return supplier;
        return () -> {
            if (lightspeed$isOpen(zip)) return supplier.get();
            ZipFile reopened = lightspeed$getOpenZipFile();
            if (reopened == null) throw lightspeed$missingResource(entry);
            ZipEntry current = reopened.getEntry(entry.getName());
            if (current == null) throw lightspeed$missingResource(entry);
            return reopened.getInputStream(current);
        };
    }

    @Inject(method = "close", at = @At("HEAD"))
    public void closeHeadInjected(CallbackInfo ci) {
        lightspeed$clearIndex();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void lightspeed$closed(CallbackInfo ci) {
        lightspeed$clearIndex();
    }

    @Unique
    private OrderedZipEntryIndex lightspeed$getEntries(ZipFile zip) {
        OrderedZipEntryIndex current = lightspeed$entries;
        if (current != null && current.matches(zip)) {
            return current;
        }
        synchronized (this) {
            current = lightspeed$entries;
            if (current == null || !current.matches(zip)) {
                current = OrderedZipEntryIndex.create(zip);
                lightspeed$entries = current;
            }
            return current;
        }
    }

    @Unique
    private synchronized void lightspeed$clearIndex() {
        lightspeed$entries = null;
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
    private static FileNotFoundException lightspeed$missingResource(ZipEntry entry) {
        String name = entry.getName();
        int first = name.indexOf('/');
        int second = name.indexOf('/', first + 1);
        ResourceLocation location = ResourceLocation.tryBuild(name.substring(first + 1, second), name.substring(second + 1));
        return new FileNotFoundException(location.toString());
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        lightspeed$clearIndex();
    }
}
