package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    private void getResourceHeadInjected(ResourceLocation location, CallbackInfoReturnable<Optional<Resource>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldParallelizeResourcePackLookup || this.fallbacks.size() <= 1) {
            return;
        }

        List<CompletableFuture<IoSupplier<InputStream>>> futures = new ArrayList<>(this.fallbacks.size());
        for (Object entry : this.fallbacks) {
            FallbackResourceManagerPackEntryAccessor accessor = (FallbackResourceManagerPackEntryAccessor) entry;
            PackResources packResources = accessor.lightspeed$resources();
            futures.add(packResources == null ? null : CompletableFuture.supplyAsync(() -> packResources.getResource(this.type, location), GlobalCache.EXECUTOR));
        }

        for (int i = this.fallbacks.size() - 1; i >= 0; i--) {
            Object entry = this.fallbacks.get(i);
            FallbackResourceManagerPackEntryAccessor accessor = (FallbackResourceManagerPackEntryAccessor) entry;
            PackResources packResources = accessor.lightspeed$resources();
            if (packResources != null) {
                IoSupplier<InputStream> supplier = lightspeed$getResourceFuture(futures.get(i), location);
                if (supplier != null) {
                    cir.setReturnValue(Optional.of(new Resource(packResources, supplier, lightspeed$createStackMetadataFinder(location, i))));
                    return;
                }
            }

            if (accessor.lightspeed$isFiltered(location)) {
                LOGGER.warn("Resource {} not found, but was filtered by pack {}", location, accessor.lightspeed$name());
                cir.setReturnValue(Optional.empty());
                return;
            }
        }

        cir.setReturnValue(Optional.empty());
    }

    private IoSupplier<InputStream> lightspeed$getResourceFuture(CompletableFuture<IoSupplier<InputStream>> future, ResourceLocation location) {
        if (future == null) {
            return null;
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            LOGGER.warn("Lightspeed parallel resource lookup failed for {}", location, e);
            return null;
        }
    }
}
