package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Mixin(FallbackResourceManager.class)
public abstract class FallbackResourceManagerMixin {
    @Shadow @Final private static Logger LOGGER;
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

        List<IndexedPack> safeSegment = new ArrayList<>();
        for (int i = this.fallbacks.size() - 1; i >= 0; i--) {
            FallbackResourceManagerPackEntryAccessor entry = (FallbackResourceManagerPackEntryAccessor) this.fallbacks.get(i);
            PackResources packResources = entry.lightspeed$resources();

            if (packResources != null && entry.lightspeed$filter() == null && lightspeed$isSafeForParallelLookup(packResources)) {
                safeSegment.add(new IndexedPack(i, packResources));
                continue;
            }

            Optional<Resource> resource = lightspeed$searchSafeSegment(safeSegment, location);
            if (resource != null) {
                cir.setReturnValue(resource);
                return;
            }
            safeSegment.clear();

            if (packResources != null) {
                IoSupplier<InputStream> supplier = packResources.getResource(this.type, location);
                if (supplier != null) {
                    cir.setReturnValue(Optional.of(lightspeed$createResource(
                            packResources, location, supplier, lightspeed$createStackMetadataFinder(location, i))));
                    return;
                }
            }

            if (entry.lightspeed$isFiltered(location)) {
                LOGGER.warn("Resource {} not found, but was filtered by pack {}", location, entry.lightspeed$name());
                cir.setReturnValue(Optional.empty());
                return;
            }
        }

        Optional<Resource> resource = lightspeed$searchSafeSegment(safeSegment, location);
        cir.setReturnValue(resource == null ? Optional.empty() : resource);
    }

    @Unique
    private Optional<Resource> lightspeed$searchSafeSegment(List<IndexedPack> segment, ResourceLocation location) {
        if (segment.isEmpty()) {
            return null;
        }
        Optional<Optional<Resource>> indexedSearch = lightspeed$searchIndexedSegment(segment, location);
        if (indexedSearch.isPresent()) {
            return indexedSearch.get().isPresent() ? indexedSearch.get() : null;
        }
        if (segment.size() < GlobalCache.parallelLookupMinPacks) {
            return lightspeed$searchSafeSegmentSequential(segment, location);
        }

        if (segment.size() == 1) {
            IndexedPack indexedPack = segment.get(0);
            IoSupplier<InputStream> supplier = indexedPack.pack().getResource(this.type, location);
            return supplier == null ? null : Optional.of(lightspeed$createResource(indexedPack.pack(), location,
                    supplier, lightspeed$createStackMetadataFinder(location, indexedPack.index())));
        }

        List<CompletableFuture<IoSupplier<InputStream>>> futures = new ArrayList<>(segment.size());
        try {
            for (IndexedPack indexedPack : segment) {
                futures.add(CompletableFuture.supplyAsync(() -> indexedPack.pack().getResource(this.type, location), GlobalCache.EXECUTOR));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Lightspeed parallel resource lookup rejected for {}; falling back to vanilla order", location, e);
            return lightspeed$searchSafeSegmentSequential(segment, location);
        }

        for (int i = 0; i < segment.size(); i++) {
            IndexedPack indexedPack = segment.get(i);
            try {
                IoSupplier<InputStream> supplier = futures.get(i).get();
                if (supplier != null) {
                    return Optional.of(lightspeed$createResource(indexedPack.pack(), location,
                            supplier, lightspeed$createStackMetadataFinder(location, indexedPack.index())));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return lightspeed$searchSafeSegmentSequential(segment, location);
            } catch (ExecutionException e) {
                LOGGER.warn("Lightspeed parallel resource lookup failed for {} in {}", location, indexedPack.pack().packId(), e);
            }
        }
        return null;
    }

    @Unique
    private Optional<Optional<Resource>> lightspeed$searchIndexedSegment(List<IndexedPack> segment, ResourceLocation location) {
        for (IndexedPack indexedPack : segment) {
            if (!(indexedPack.pack() instanceof IPathResourcePack indexedPackResources)) {
                return Optional.empty();
            }
            Boolean present = indexedPackResources.lightspeed$hasIndexedResource(this.type, location);
            if (present == null) {
                return Optional.empty();
            }
            if (present) {
                IoSupplier<InputStream> supplier = indexedPack.pack().getResource(this.type, location);
                if (supplier == null) {
                    return Optional.empty();
                }
                return Optional.of(Optional.of(lightspeed$createResource(
                        indexedPack.pack(), location, supplier,
                        lightspeed$createStackMetadataFinder(location, indexedPack.index()))));
            }
        }
        return Optional.of(Optional.empty());
    }

    @Unique
    private Optional<Resource> lightspeed$searchSafeSegmentSequential(List<IndexedPack> segment, ResourceLocation location) {
        for (IndexedPack indexedPack : segment) {
            IoSupplier<InputStream> supplier = indexedPack.pack().getResource(this.type, location);
            if (supplier != null) {
                return Optional.of(lightspeed$createResource(indexedPack.pack(), location,
                        supplier, lightspeed$createStackMetadataFinder(location, indexedPack.index())));
            }
        }
        return null;
    }

    @Unique
    private static boolean lightspeed$isSafeForParallelLookup(PackResources packResources) {
        Class<?> packClass = packResources.getClass();
        return (packClass == PathPackResources.class || packClass == FilePackResources.class)
                && !FusionPackCompat.hasOverrides(packResources);
    }

    @Unique
    private record IndexedPack(int index, PackResources pack) {
    }

}
