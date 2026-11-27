package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourceListingScope;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackEntry;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackLookup;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

@Mixin(FallbackResourceManager.class)
public abstract class FallbackResourceManagerMixin implements ResourcePackLookup.GroupedAccess<ResourceLocation> {
    @Shadow @Final public List<?> fallbacks;
    @Shadow @Final private PackType type;
    @Shadow @Final private String namespace;

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
                && Boolean.FALSE.equals(lightspeed$packHasAnyUnder(pack, packType, ns, path))) {
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
            if (!Boolean.FALSE.equals(lightspeed$packHasAnyUnder(pack, type, namespace, path))) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private static Boolean lightspeed$packHasAnyUnder(PackResources pack, PackType packType, String ns, String path) {
        if (pack instanceof IndexedPackResources indexed) {
            return indexed.lightspeed$hasAnyUnder(packType, ns, path);
        }
        if (pack instanceof CompositePackResources) {
            List<PackResources> stack = ((CompositePackResourcesAccessor) (Object) pack).lightspeed$getPackResourcesStack();
            if (stack == null || stack.isEmpty()) {
                return Boolean.FALSE;
            }
            for (int i = 0; i < stack.size(); i++) {
                Boolean sub = lightspeed$packHasAnyUnder(stack.get(i), packType, ns, path);
                if (sub == null) {
                    return null;
                }
                if (sub) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
        return null;
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
        if (!GlobalCache.isEnabled || !GlobalCache.shouldParallelizeResourcePackLookup || this.fallbacks.size() <= 1) {
            return;
        }

        cir.setReturnValue(ResourcePackLookup.grouped(fallbacks, location, this));
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
        return lightspeed$createResource(pack, location, stream, lightspeed$createStackMetadataFinder(location, index));
    }

    @Override
    public Boolean lightspeed$lookupContains(PackResources pack, ResourceLocation location) {
        if (pack instanceof IPathResourcePack indexed) {
            return indexed.lightspeed$hasIndexedResource(type, location);
        }
        if (pack instanceof IndexedPackResources indexed) {
            return indexed.lightspeed$containsIndexed(type.getDirectory(), location.getNamespace(), location.getPath());
        }
        return null;
    }

    @Override
    public boolean lightspeed$canGroup(PackResources packResources) {
        Class<?> packClass = packResources.getClass();
        return (packClass == PathPackResources.class || packClass == FilePackResources.class)
                && !FusionPackCompat.hasOverrides(packResources);
    }
}
