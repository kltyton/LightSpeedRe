package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathIndex;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathValidationCache;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import com.ccr4ft3r.lightspeed.interfaces.NamespaceFilteredPack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.resource.DelegatingPackResources;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(DelegatingPackResources.class)
public abstract class DelegatingResourcePackMixin implements NamespaceFilteredPack, IndexedPackResources {
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
    @Unique private boolean lightspeed$assetsLowercase;
    @Unique private boolean lightspeed$dataLowercase;
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
        lightspeed$assetsLowercase = lightspeed$allLowercase(namespacesAssets);
        lightspeed$dataLowercase = lightspeed$allLowercase(namespacesData);
    }

    @Unique
    private static boolean lightspeed$allLowercase(Map<String, List<PackResources>> map) {
        for (String key : map.keySet()) {
            if (!key.equals(key.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private List<PackResources> lightspeed$namespaceCandidates(PackType type, String namespace) {
        boolean isAssets = type == PackType.CLIENT_RESOURCES;
        if (isAssets ? !lightspeed$assetsLowercase : !lightspeed$dataLowercase) {
            return null;
        }
        Map<String, List<PackResources>> namespaces = isAssets ? namespacesAssets : namespacesData;
        Map<String, List<PackResources>> routes = isAssets ? lightspeed$assetDelegates : lightspeed$dataDelegates;
        return routes.computeIfAbsent(namespace, ignored -> {
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
    }

    @Override
    public List<PackResources> lightspeed$candidatesForStack(PackType type, String namespace,
            Collection<PackResources> children) {
        if (!GlobalCache.isEnabled || children != delegates
                || delegates.size() != lightspeed$expectedDelegateCount
                || lightspeed$staticDelegates.isEmpty()) {
            return null;
        }
        return lightspeed$namespaceCandidates(type, namespace);
    }

    @Override
    public Boolean lightspeed$containsIndexed(String type, String namespace, String path) {
        return null;
    }

    @Override
    public Boolean lightspeed$hasAnyUnder(PackType type, String namespace, String path) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths
                || delegates.size() != lightspeed$expectedDelegateCount
                || lightspeed$staticDelegates.isEmpty()
                || lightspeed$pathValidation.error(path).isPresent()) {
            return null;
        }
        List<PackResources> filtered = lightspeed$namespaceCandidates(type, namespace);
        if (filtered == null) {
            return null;
        }
        if (filtered.isEmpty()) {
            return Boolean.FALSE;
        }
        for (int i = 0; i < filtered.size(); i++) {
            PackResources delegate = filtered.get(i);
            if (!lightspeed$staticDelegates.contains(delegate) || !(delegate instanceof IndexedPackResources indexed)) {
                return null;
            }
            Boolean hasAny = indexed.lightspeed$hasAnyUnder(type, namespace, path);
            if (hasAny == null) {
                return null;
            }
            if (hasAny) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
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
        List<PackResources> filtered = lightspeed$namespaceCandidates(type, namespace);
        if (filtered == null) {
            return original.call(originalDelegates);
        }
        if (filtered.isEmpty()) {
            return Collections.emptyIterator();
        }
        if (!GlobalCache.shouldCacheWalkedPaths) {
            return filtered.iterator();
        }
        int count = filtered.size();
        if (count == 1) {
            PackResources only = filtered.get(0);
            if (lightspeed$staticDelegates.contains(only)
                    && only instanceof IndexedPackResources indexed
                    && Boolean.FALSE.equals(indexed.lightspeed$hasAnyUnder(type, namespace, path))) {
                return Collections.emptyIterator();
            }
            return filtered.iterator();
        }
        List<PackResources> active = null;
        for (int i = 0; i < count; i++) {
            PackResources delegate = filtered.get(i);
            boolean empty = lightspeed$staticDelegates.contains(delegate)
                    && delegate instanceof IndexedPackResources indexed
                    && Boolean.FALSE.equals(indexed.lightspeed$hasAnyUnder(type, namespace, path));
            if (empty) {
                if (active == null) {
                    active = new ArrayList<>(Math.min(count, 16));
                    for (int prev = 0; prev < i; prev++) {
                        active.add(filtered.get(prev));
                    }
                }
            } else if (active != null) {
                active.add(delegate);
            }
        }
        if (active == null) {
            return filtered.iterator();
        }
        return active.isEmpty() ? Collections.emptyIterator() : active.iterator();
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    private void lightspeed$getIndexedDelegateResource(PackType type, ResourceLocation location,
            CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || type != PackType.CLIENT_RESOURCES
                || !lightspeed$assetsLowercase
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
