package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathIndex;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathValidationCache;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.resource.DelegatingPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(DelegatingPackResources.class)
public abstract class DelegatingResourcePackMixin {
    @Shadow(remap = false) @Final private List<PackResources> delegates;
    @Shadow(remap = false) @Final private Map<String, List<PackResources>> namespacesAssets;
    @Shadow(remap = false) @Final private Map<String, List<PackResources>> namespacesData;
    @Unique private final Set<PackResources> lightspeed$staticDelegates =
            Collections.newSetFromMap(new IdentityHashMap<>());
    @Unique private final Map<String, List<PackResources>> lightspeed$assetDelegates = new ConcurrentHashMap<>();
    @Unique private final Map<String, List<PackResources>> lightspeed$dataDelegates = new ConcurrentHashMap<>();
    @Unique private final Map<String, Object> lightspeed$assetRouteMaps = new ConcurrentHashMap<>();
    @Unique private static final Object LIGHTSPEED$UNINDEXED = new Object();
    @Unique private int lightspeed$expectedDelegateCount = -1;
    @Unique private boolean lightspeed$hasNonStaticDelegates;
    @Unique private final ResourcePathValidationCache lightspeed$pathValidation = new ResourcePathValidationCache();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lightspeed$identifyStaticDelegates(CallbackInfo callback) {
        lightspeed$expectedDelegateCount = delegates.size();
        boolean nonStatic = false;
        for (PackResources delegate : delegates) {
            if (delegate.getClass().getName().equals("net.minecraftforge.resource.ResourcePackLoader$1")
                    && !FusionPackCompat.hasOverrides(delegate)) {
                lightspeed$staticDelegates.add(delegate);
            } else {
                nonStatic = true;
            }
        }
        lightspeed$hasNonStaticDelegates = nonStatic;
    }

    @WrapOperation(method = "listResources", at = @At(value = "INVOKE",
            target = "Ljava/util/List;iterator()Ljava/util/Iterator;", remap = false))
    private Iterator<PackResources> lightspeed$listCandidatePacks(List<PackResources> originalDelegates,
            Operation<Iterator<PackResources>> original, PackType type, String namespace, String path,
            PackResources.ResourceOutput output) {
        if (!GlobalCache.isEnabled || originalDelegates != delegates
                || originalDelegates.size() != lightspeed$expectedDelegateCount
                || lightspeed$staticDelegates.isEmpty()
                || lightspeed$pathValidation.error(path).isPresent()) {
            return original.call(originalDelegates);
        }
        Map<String, List<PackResources>> namespaces = type == PackType.CLIENT_RESOURCES ? namespacesAssets : namespacesData;
        Map<String, List<PackResources>> routes = type == PackType.CLIENT_RESOURCES
                ? lightspeed$assetDelegates : lightspeed$dataDelegates;
        List<PackResources> filtered = routes.computeIfAbsent(namespace, ignored -> {
            List<PackResources> knownForNs = namespaces.get(namespace);
            if (!lightspeed$hasNonStaticDelegates) {
                return knownForNs == null ? List.of() : knownForNs;
            }
            Set<PackResources> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
            if (knownForNs != null) {
                candidates.addAll(knownForNs);
            }
            List<PackResources> selected = new ArrayList<>(knownForNs == null ? 8 : knownForNs.size() + 4);
            for (PackResources delegate : delegates) {
                if (!lightspeed$staticDelegates.contains(delegate) || candidates.contains(delegate)) {
                    selected.add(delegate);
                }
            }
            return selected.isEmpty() ? List.of() : List.copyOf(selected);
        });
        return filtered.iterator();
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    private void lightspeed$getIndexedDelegateResource(PackType type, ResourceLocation location,
            CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || type != PackType.CLIENT_RESOURCES
                || lightspeed$staticDelegates.isEmpty() || lightspeed$hasNonStaticDelegates
                || delegates.size() != lightspeed$expectedDelegateCount) {
            return;
        }
        String namespace = location.getNamespace();
        Object routeObj = lightspeed$assetRouteMaps.computeIfAbsent(namespace, ns -> {
            List<PackResources> candidates = namespacesAssets.get(ns);
            if (candidates == null || candidates.size() < 4) {
                return LIGHTSPEED$UNINDEXED;
            }
            Map<String, PackResources> ownerByPath = new HashMap<>(64);
            for (PackResources pack : candidates) {
                if (!(pack instanceof IPathResourcePack indexed) || !lightspeed$staticDelegates.contains(pack)) {
                    return LIGHTSPEED$UNINDEXED;
                }
                ResourcePathIndex pathIndex = indexed.lightspeed$earlyPathIndex(PackType.CLIENT_RESOURCES, ns);
                if (pathIndex == null) {
                    return LIGHTSPEED$UNINDEXED;
                }
                for (String path : pathIndex.rawSortedPaths()) {
                    ownerByPath.putIfAbsent(path, pack);
                }
            }
            return ownerByPath;
        });
        if (routeObj == LIGHTSPEED$UNINDEXED) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, PackResources> ownerByPath = (Map<String, PackResources>) routeObj;
        PackResources owner = ownerByPath.get(location.getPath());
        cir.setReturnValue(owner == null ? null : owner.getResource(type, location));
    }
}
