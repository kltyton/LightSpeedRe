package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackLookup;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackEntry;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import net.minecraft.resources.ResourceLocation;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

@Mixin(FallbackResourceManager.class)
public abstract class FallbackResourceManagerMixin implements ResourcePackLookup.GroupedAccess<ResourceLocation> {
    @Shadow @Final public List<?> fallbacks;
    @Shadow @Final private PackType type;

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
        if (pack instanceof IndexedPackResources indexed) {
            return indexed.lightspeed$containsIndexed(type.getDirectory(), location.getNamespace(), location.getPath());
        }
        return pack instanceof IPathResourcePack indexed ? indexed.lightspeed$hasIndexedResource(type, location) : null;
    }

    @Override
    public boolean lightspeed$canGroup(PackResources packResources) {
        Class<?> packClass = packResources.getClass();
        return (packClass == PathPackResources.class || packClass == FilePackResources.class)
                && !FusionPackCompat.hasOverrides(packResources);
    }


}
