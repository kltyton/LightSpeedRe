package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourceListingScope;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackEntry;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackLookup;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Skips proven snapshot misses while retaining pack priority, filters, metadata, and native suppliers. */
@Mixin(FallbackResourceManager.class)
public abstract class FallbackResourceManagerMixin implements ResourcePackLookup.Access<Identifier> {
    @Shadow @Final protected List<?> fallbacks;
    @Shadow @Final private PackType type;
    @Shadow @Final private String namespace;

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    private void lightspeed$skipEmptyListResources(String path, Predicate<Identifier> filter,
            CallbackInfoReturnable<Map<Identifier, Resource>> cir) {
        if (lightspeed$allPacksEmptyUnder(path)) {
            cir.setReturnValue(ResourceListingScope.bulk() ? Map.of() : new TreeMap<>());
        }
    }

    @Inject(method = "listResourceStacks", at = @At("HEAD"), cancellable = true)
    private void lightspeed$skipEmptyListResourceStacks(String path, Predicate<Identifier> filter,
            CallbackInfoReturnable<Map<Identifier, List<Resource>>> cir) {
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
                && !FusionPackCompat.hasOverrides(pack)
                && Boolean.FALSE.equals(indexed.lightspeed$hasAnyUnder(packType, ns, path))) {
            return;
        }
        original.call(pack, packType, ns, path, output);
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
                    || FusionPackCompat.hasOverrides(pack)
                    || !Boolean.FALSE.equals(indexed.lightspeed$hasAnyUnder(type, namespace, path))) {
                return false;
            }
        }
        return true;
    }

    @Invoker("createStackMetadataFinder")
    protected abstract IoSupplier<ResourceMetadata> lightspeed$metadata(Identifier location, int index);

    @Invoker("createResource")
    private static Resource lightspeed$resource(PackResources pack, Identifier location,
            IoSupplier<InputStream> supplier, IoSupplier<ResourceMetadata> metadata) {
        throw new AssertionError();
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    private void lightspeed$findIndexed(Identifier location, CallbackInfoReturnable<Optional<Resource>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldParallelizeResourcePackLookup) {
            return;
        }
        cir.setReturnValue(ResourcePackLookup.indexed(fallbacks, location, this));
    }

    @Override
    public boolean lightspeed$lookupFiltered(ResourcePackEntry entry, Identifier location) {
        return ((FallbackResourceManagerPackEntryAccessor) entry).lightspeed$isFiltered(location);
    }

    @Override
    public IoSupplier<InputStream> lightspeed$lookupRead(PackResources pack, Identifier location) {
        return pack.getResource(type, location);
    }

    @Override
    public Resource lightspeed$lookupResource(PackResources pack, Identifier location, IoSupplier<InputStream> stream, int index) {
        return lightspeed$resource(pack, location, stream, lightspeed$metadata(location, index));
    }

    @Override
    public Boolean lightspeed$lookupContains(PackResources pack, Identifier location) {
        return pack instanceof IndexedPackResources indexed && !FusionPackCompat.hasOverrides(pack)
                ? indexed.lightspeed$containsIndexed(type.getDirectory(), location.getNamespace(), location.getPath()) : null;
    }
}
