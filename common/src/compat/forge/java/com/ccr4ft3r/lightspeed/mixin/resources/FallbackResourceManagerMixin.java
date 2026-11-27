package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourceListingScope;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackEntry;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackLookup;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.ICache;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import com.ccr4ft3r.lightspeed.interfaces.NamespaceFilteredPack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraftforge.resource.PathPackResources;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Mixin(FallbackResourceManager.class)
public abstract class FallbackResourceManagerMixin implements ResourcePackLookup.GroupedAccess<ResourceLocation>, ICache {
    @Shadow @Final public List<?> fallbacks;
    @Shadow @Final private PackType type;
    @Shadow @Final private String namespace;
    @Unique private final Map<ResourceLocation, Object> lightspeed$routeWinners = new ConcurrentHashMap<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lightspeed$registerRoutes(CallbackInfo callback) {
        if (GlobalCache.isEnabled) GlobalCache.add(this);
    }

    @ModifyVariable(method = "listResources", at = @At("STORE"), ordinal = 2)
    private Map<ResourceLocation, Resource> lightspeed$skipIntermediateSort(Map<ResourceLocation, Resource> resources) {
        return ResourceListingScope.bulk() ? new HashMap<>() : resources;
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    private void lightspeed$skipEmptyListResources(String path, Predicate<ResourceLocation> filter,
            CallbackInfoReturnable<Map<ResourceLocation, Resource>> cir) {
        if (lightspeed$allPacksEmptyUnder(path)) {
            cir.setReturnValue(ResourceListingScope.bulk() ? Map.of() : new TreeMap<>());
        }
    }

    @Inject(method = "listResourceStacks", at = @At("HEAD"), cancellable = true)
    private void lightspeed$skipEmptyListResourceStacks(String path, Predicate<ResourceLocation> filter,
            CallbackInfoReturnable<Map<ResourceLocation, List<Resource>>> cir) {
        if (lightspeed$allPacksEmptyUnder(path)) {
            cir.setReturnValue(ResourceListingScope.bulk() ? Map.of() : new TreeMap<>());
        }
    }

    @WrapOperation(method = {"listResources", "listPackResources"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/PackResources;listResources(Lnet/minecraft/server/packs/PackType;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V"))
    private void lightspeed$skipEmptyPackListing(PackResources pack, PackType packType, String ns, String path,
            PackResources.ResourceOutput output, Operation<Void> original) {
        if (GlobalCache.isEnabled && GlobalCache.shouldCacheWalkedPaths
                && pack instanceof IndexedPackResources indexed
                && Boolean.FALSE.equals(indexed.lightspeed$hasAnyUnder(packType, ns, path))) {
            return;
        }
        original.call(pack, packType, ns, path, output);
    }

    @WrapOperation(method = "getResourceStack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/PackResources;getChildren()Ljava/util/Collection;", remap = false),
            require = 0)
    private Collection<PackResources> lightspeed$filterStackChildren(PackResources pack,
            Operation<Collection<PackResources>> original, ResourceLocation location) {
        Collection<PackResources> children = original.call(pack);
        if (!GlobalCache.isEnabled || children == null || !(pack instanceof NamespaceFilteredPack filtered)) {
            return children;
        }
        List<PackResources> candidates = filtered.lightspeed$candidatesForStack(type, location.getNamespace(), children);
        if (candidates == null) {
            return children;
        }
        if (candidates.isEmpty() || !GlobalCache.shouldCacheWalkedPaths) {
            return candidates;
        }
        int count = candidates.size();
        if (count == 1) {
            PackResources only = candidates.get(0);
            if (only instanceof IPathResourcePack indexed
                    && !FusionPackCompat.hasOverrides(only)
                    && Boolean.FALSE.equals(indexed.lightspeed$hasIndexedResource(type, location))) {
                return List.of();
            }
            return candidates;
        }
        List<PackResources> present = null;
        for (int i = 0; i < count; i++) {
            PackResources child = candidates.get(i);
            boolean absent = child instanceof IPathResourcePack indexed
                    && !FusionPackCompat.hasOverrides(child)
                    && Boolean.FALSE.equals(indexed.lightspeed$hasIndexedResource(type, location));
            if (absent) {
                if (present == null) {
                    present = new ArrayList<>(Math.min(count, 8));
                    for (int prev = 0; prev < i; prev++) {
                        present.add(candidates.get(prev));
                    }
                }
            } else if (present != null) {
                present.add(child);
            }
        }
        return present == null ? candidates : present;
    }

    @Unique
    private boolean lightspeed$allPacksEmptyUnder(String path) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths) {
            return false;
        }
        int count = fallbacks.size();
        if (count == 0) {
            return true;
        }
        for (int i = 0; i < count; i++) {
            FallbackResourceManagerPackEntryAccessor entry =
                    (FallbackResourceManagerPackEntryAccessor) fallbacks.get(i);
            PackResources pack = entry.lightspeed$resources();
            if (pack == null) {
                continue;
            }
            if (!(pack instanceof IndexedPackResources indexed)
                    || !Boolean.FALSE.equals(indexed.lightspeed$hasAnyUnder(type, namespace, path))) {
                return false;
            }
        }
        return true;
    }

    @Invoker("createStackMetadataFinder")
    protected abstract IoSupplier<ResourceMetadata> lightspeed$createStackMetadataFinder(ResourceLocation location, int index);

    @Invoker("createResource")
    private static Resource lightspeed$createResource(PackResources pack, ResourceLocation location,
                                                      IoSupplier<InputStream> resource,
                                                      IoSupplier<ResourceMetadata> metadata) {
        throw new AssertionError();
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    private void getResourceHeadInjected(ResourceLocation location, CallbackInfoReturnable<Optional<Resource>> cir) {
        if (GlobalCache.isEnabled) {
            Object winner = lightspeed$routeWinners.get(location);
            if (winner instanceof IndexedPack indexedWinner) {
                Optional<Resource> result = ResourcePackLookup.cached(fallbacks, location,
                        indexedWinner.index(), indexedWinner.pack(), this);
                if (result.isEmpty()) lightspeed$routeWinners.remove(location, indexedWinner);
                cir.setReturnValue(result);
                return;
            }
        }
        if (!GlobalCache.isEnabled || !GlobalCache.shouldParallelizeResourcePackLookup || this.fallbacks.size() <= 1) {
            return;
        }

        Optional<Resource> result = ResourcePackLookup.grouped(fallbacks, location, this);
        cir.setReturnValue(result);
    }

    @Inject(method = "getResource", at = @At("RETURN"))
    private void lightspeed$recordRouteWinner(ResourceLocation location,
                                               CallbackInfoReturnable<Optional<Resource>> cir) {
        if (!GlobalCache.isEnabled) {
            return;
        }
        if (cir.getReturnValue().isEmpty()) return;
        String sourcePack = cir.getReturnValue().get().sourcePackId();
        for (int index = this.fallbacks.size() - 1; index >= 0; index--) {
            FallbackResourceManagerPackEntryAccessor entry =
                    (FallbackResourceManagerPackEntryAccessor) this.fallbacks.get(index);
            PackResources pack = entry.lightspeed$resources();
            if (pack != null && pack.packId().equals(sourcePack)) {
                lightspeed$routeWinners.put(location, new IndexedPack(index, pack));
                return;
            }
        }
    }

    @Override
    public boolean lightspeed$lookupFiltered(ResourcePackEntry entry, ResourceLocation location) {
        return ((FallbackResourceManagerPackEntryAccessor) entry).lightspeed$isFiltered(location);
    }

    @Override
    public IoSupplier<InputStream> lightspeed$lookupRead(PackResources pack, ResourceLocation location) {
        return pack.getResource(type, location);
    }

    @Override
    public Resource lightspeed$lookupResource(PackResources pack, ResourceLocation location, IoSupplier<InputStream> stream, int index) {
        if (GlobalCache.isEnabled) lightspeed$routeWinners.put(location, new IndexedPack(index, pack));
        return lightspeed$createResource(pack, location, stream, lightspeed$createStackMetadataFinder(location, index));
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        lightspeed$routeWinners.clear();
    }

    @Override
    public Boolean lightspeed$lookupContains(PackResources pack, ResourceLocation location) {
        if (pack instanceof IPathResourcePack indexed) {
            return indexed.lightspeed$hasIndexedResource(type, location);
        }
        return pack instanceof IndexedPackResources indexed
                ? indexed.lightspeed$containsIndexed(type.getDirectory(), location.getNamespace(), location.getPath()) : null;
    }

    @Override
    public boolean lightspeed$canGroup(PackResources packResources) {
        Class<?> packClass = packResources.getClass();
        boolean forgeModPathPack = packResources instanceof PathPackResources
                && (packClass == PathPackResources.class || packClass.getName().startsWith("net.minecraftforge.resource.ResourcePackLoader$"));
        return (forgeModPathPack || packClass == FilePackResources.class) && !FusionPackCompat.hasOverrides(packResources);
    }

    @Unique
    private record IndexedPack(int index, PackResources pack) {
    }
}
