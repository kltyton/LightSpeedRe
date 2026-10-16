package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import net.minecraft.resources.Identifier;
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
import java.util.List;
import java.util.Optional;

/** Skips proven snapshot misses while retaining pack priority, filters, metadata, and native suppliers. */
@Mixin(FallbackResourceManager.class)
public abstract class FallbackResourceManagerMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final protected List<?> fallbacks;
    @Shadow @Final private PackType type;

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
        for (int index = fallbacks.size() - 1; index >= 0; index--) {
            FallbackResourceManagerPackEntryAccessor entry =
                    (FallbackResourceManagerPackEntryAccessor) fallbacks.get(index);
            PackResources pack = entry.lightspeed$resources();
            if (pack != null) {
                Boolean present = pack instanceof IndexedPackResources indexed && !FusionPackCompat.hasOverrides(pack)
                        ? indexed.lightspeed$containsIndexed(type.getDirectory(), location.getNamespace(), location.getPath())
                        : null;
                if (!Boolean.FALSE.equals(present)) {
                    IoSupplier<InputStream> supplier = pack.getResource(type, location);
                    if (supplier != null) {
                        cir.setReturnValue(Optional.of(lightspeed$resource(pack, location, supplier,
                                lightspeed$metadata(location, index))));
                        return;
                    }
                }
            }
            if (entry.lightspeed$isFiltered(location)) {
                LOGGER.warn("Resource {} not found, but was filtered by pack {}", location, entry.lightspeed$name());
                cir.setReturnValue(Optional.empty());
                return;
            }
        }
        cir.setReturnValue(Optional.empty());
    }
}
