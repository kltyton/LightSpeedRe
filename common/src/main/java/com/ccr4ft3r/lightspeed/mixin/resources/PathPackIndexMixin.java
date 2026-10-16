package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.PackPathIndex;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathValidationCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathRules;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Indexes vanilla path packs without changing custom pack implementations or path validation. */
@Mixin(PathPackResources.class)
public abstract class PathPackIndexMixin implements IPackResources, IndexedPackResources {
    @Shadow @Final private Path root;
    @Unique private PackPathIndex lightspeed$index;
    @Unique private final Map<PackType, Set<String>> lightspeed$namespaces = new ConcurrentHashMap<>();
    @Unique private final ResourcePathValidationCache lightspeed$validation = new ResourcePathValidationCache();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lightspeed$bindPath(CallbackInfo ci) {
        if (GlobalCache.isEnabled) {
            lightspeed$index = new PackPathIndex("path", List.of(root));
            GlobalCache.add(this);
        }
    }

    @Inject(method = "getNamespaces(Lnet/minecraft/server/packs/PackType;)Ljava/util/Set;",
            at = @At("HEAD"), cancellable = true)
    private void lightspeed$cachedNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (lightspeed$ownsPath()) {
            Set<String> names = lightspeed$namespaces.get(type);
            if (names != null) {
                cir.setReturnValue(names);
            }
        }
    }

    @Inject(method = "getNamespaces(Lnet/minecraft/server/packs/PackType;)Ljava/util/Set;", at = @At("RETURN"))
    private void lightspeed$recordNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (lightspeed$ownsPath() && (GlobalCache.shouldCacheEmptyNamespaces || !cir.getReturnValue().isEmpty())) {
            lightspeed$namespaces.put(type, cir.getReturnValue());
        }
    }

    @Inject(method = "listResources(Lnet/minecraft/server/packs/PackType;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V",
            at = @At("HEAD"), cancellable = true)
    private void lightspeed$listIndexed(PackType type, String namespace, String directory,
            PackResources.ResourceOutput output, CallbackInfo ci) {
        if (!lightspeed$ownsPath() || !GlobalCache.shouldCacheWalkedPaths
                || lightspeed$validation.error(directory).isPresent()) {
            return;
        }
        if (lightspeed$index.listResources(type, namespace, directory, output, ResourcePathRules::includeFile)) {
            ci.cancel();
        }
    }

    @Unique
    private boolean lightspeed$ownsPath() {
        return GlobalCache.isEnabled && lightspeed$index != null && ((Object) this).getClass() == PathPackResources.class
                && !FusionPackCompat.hasOverrides(this);
    }

    @Override
    public Boolean lightspeed$containsIndexed(String type, String namespace, String path) {
        return lightspeed$ownsPath() ? lightspeed$index.contains(type, namespace, path) : null;
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        lightspeed$namespaces.clear();
        lightspeed$getExistenceByResource().clear();
    }
}
