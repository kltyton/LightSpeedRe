package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.PackPathIndex;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.config.FabricLightspeedConfig;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Indexes Fabric's v0 and v1 mod packs while retaining their root order and namespace checks. */
@Pseudo
@Mixin(targets = {"net.fabricmc.fabric.impl.resource.loader.ModNioResourcePack",
        "net.fabricmc.fabric.impl.resource.pack.ModNioPackResources"}, remap = false)
public abstract class FabricModPackMixin implements IndexedPackResources {
    @Shadow @Final private List<Path> basePaths;
    @Shadow @Final private Map<PackType, Set<String>> namespaces;
    @Unique private PackPathIndex lightspeed$index;

    @Shadow(remap = false)
    public abstract ModMetadata getFabricModMetadata();

    @Invoker(value = "hasAbsentNs", remap = false)
    public abstract boolean lightspeed$hasAbsentNamespace(String filename);

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void lightspeed$bindPack(CallbackInfo ci) {
        FabricLightspeedConfig.load();
        if (GlobalCache.isEnabled) {
            ModMetadata metadata = getFabricModMetadata();
            lightspeed$index = new PackPathIndex(metadata.getId() + "@" + metadata.getVersion().getFriendlyString(), basePaths);
            lightspeed$index.recordNamespaces(namespaces);
        }
    }

    @Inject(method = "readNamespaces", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lightspeed$cachedNamespaces(List<Path> roots, String modId,
            CallbackInfoReturnable<Map<PackType, Set<String>>> cir) {
        FabricLightspeedConfig.load();
        if (!GlobalCache.isEnabled) {
            return;
        }
        var container = FabricLoader.getInstance().getModContainer(modId);
        if (container.isEmpty()) {
            return;
        }
        ModMetadata metadata = container.get().getMetadata();
        Map<PackType, Set<String>> cached = PackPathIndex.cachedNamespaces(
                metadata.getId() + "@" + metadata.getVersion().getFriendlyString(), roots);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getPath", at = @At("HEAD"), cancellable = true, remap = false)
    private void lightspeed$cachedPath(String filename, CallbackInfoReturnable<Path> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence
                || lightspeed$index == null || FusionPackCompat.hasOverrides(this)) {
            return;
        }
        Optional<Path> cached = lightspeed$index.cachedPath(filename);
        if (cached != null) {
            cir.setReturnValue(lightspeed$hasAbsentNamespace(filename) ? null : cached.orElse(null));
        }
    }

    @Inject(method = "getPath", at = @At("RETURN"), remap = false)
    private void lightspeed$recordPath(String filename, CallbackInfoReturnable<Path> cir) {
        if (GlobalCache.isEnabled && GlobalCache.shouldCacheResourceExistence
                && lightspeed$index != null && !FusionPackCompat.hasOverrides(this)) {
            lightspeed$index.recordPath(filename, cir.getReturnValue());
        }
    }

    @Inject(method = {"listResources", "method_14408"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void lightspeed$listIndexed(PackType type, String namespace, String path,
            PackResources.ResourceOutput output, CallbackInfo ci) {
        if (GlobalCache.isEnabled && GlobalCache.shouldCacheWalkedPaths && lightspeed$index != null
                && namespaces.getOrDefault(type, Set.of()).contains(namespace) && !FusionPackCompat.hasOverrides(this)
                && lightspeed$index.listResources(type, namespace, path, output, (file, attributes) -> true)) {
            ci.cancel();
        }
    }

    @Override
    public Boolean lightspeed$containsIndexed(String type, String namespace, String path) {
        String className = getClass().getName();
        if (lightspeed$index == null || !(className.equals("net.fabricmc.fabric.impl.resource.loader.ModNioResourcePack")
                || className.equals("net.fabricmc.fabric.impl.resource.pack.ModNioPackResources"))) {
            return null;
        }
        return lightspeed$index.contains(type, namespace, path);
    }
}
