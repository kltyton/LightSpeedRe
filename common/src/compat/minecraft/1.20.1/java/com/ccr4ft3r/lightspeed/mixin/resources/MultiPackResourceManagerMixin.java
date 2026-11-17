package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.resource.ResourceListingCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourceListingScope;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Shares listing results and avoids intermediate sorting only when the caller merges namespaces. */
@Mixin(value = MultiPackResourceManager.class, priority = 1100)
public abstract class MultiPackResourceManagerMixin implements ResourceListingCache {
    @Unique private final Map<ListingQuery, SortedMap<ResourceLocation, Resource>> lightspeed$listings = new ConcurrentHashMap<>(256);
    @Shadow @Final private Map<String, FallbackResourceManager> namespacedManagers;

    @Shadow public abstract Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter);

    @Override
    public Map<ResourceLocation, Resource> lightspeed$listMatchingResources(String path, String extension) {
        ListingQuery query = new ListingQuery(path, extension);
        SortedMap<ResourceLocation, Resource> cached = lightspeed$listings.get(query);
        if (cached == null) {
            Map<ResourceLocation, Resource> resources = listResources(path, id -> id.getPath().endsWith(extension));
            TreeMap<ResourceLocation, Resource> computed = new TreeMap<>(resources);
            SortedMap<ResourceLocation, Resource> existing = lightspeed$listings.putIfAbsent(query, computed);
            cached = existing != null ? existing : computed;
        }
        // Each caller owns a mutable result. TreeMap's sorted-copy constructor is linear.
        return new TreeMap<>(cached);
    }

    @WrapOperation(method = "listResources", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/FallbackResourceManager;listResources(Ljava/lang/String;Ljava/util/function/Predicate;)Ljava/util/Map;"))
    private Map<ResourceLocation, Resource> lightspeed$sortMergedListingOnce(FallbackResourceManager manager, String path,
            Predicate<ResourceLocation> filter, Operation<Map<ResourceLocation, Resource>> original) {
        // A single-namespace implementation may return the fallback map without an outer sort.
        if (namespacedManagers.size() <= 1) {
            return original.call(manager, path, filter);
        }
        boolean previous = ResourceListingScope.enter();
        try {
            return original.call(manager, path, filter);
        } finally {
            ResourceListingScope.leave(previous);
        }
    }

    @Unique private record ListingQuery(String path, String extension) { }
}
