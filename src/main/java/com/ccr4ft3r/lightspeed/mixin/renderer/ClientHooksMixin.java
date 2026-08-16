package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.compat.ResourceReloadFailureGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.neoforged.neoforge.client.ClientHooks;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Keeps one broken layer definition supplier from aborting the whole model layer reload. */
@Mixin(value = ClientHooks.class, remap = false)
public abstract class ClientHooksMixin {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();

    @WrapOperation(
            method = "loadLayerDefinitions",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", remap = false),
            remap = false
    )
    private static void lightspeed$isolateLayerDefinitionFailure(
            Map<ModelLayerLocation, Supplier<LayerDefinition>> layerDefinitions,
            BiConsumer<ModelLayerLocation, Supplier<LayerDefinition>> action,
            Operation<Void> original) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures) {
            original.call(layerDefinitions, action);
            return;
        }

        BiConsumer<ModelLayerLocation, Supplier<LayerDefinition>> guardedAction = (location, supplier) -> {
            try {
                action.accept(location, supplier);
            } catch (RuntimeException failure) {
                if (!ResourceReloadFailureGuard.shouldIsolateRendererFailure(location.getModel().getNamespace(), supplier, failure)) {
                    throw failure;
                }
                lightspeed$logSkippedLayer(location, supplier, failure);
            }
        };
        original.call(layerDefinitions, guardedAction);
    }

    private static void lightspeed$logSkippedLayer(ModelLayerLocation location, Supplier<LayerDefinition> supplier, RuntimeException failure) {
        if (LOGGED_FAILURES.add(location.toString())) {
            LOGGER.warn("Lightspeed skipped failed model layer {} from {}: {}",
                    location, supplier.getClass().getName(), failure.toString());
            LOGGER.debug("Model layer failure details for {}", location, failure);
        }
    }
}
