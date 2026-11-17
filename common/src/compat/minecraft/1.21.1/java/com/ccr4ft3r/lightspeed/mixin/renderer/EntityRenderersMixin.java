package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.ccr4ft3r.lightspeed.client.renderer.CapturingMapBuilder;
import com.ccr4ft3r.lightspeed.client.renderer.DeferredRendererMap;
import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.BiConsumer;

/** Defers entity renderer creation until first access while preserving all third-party lambda hooks. */
@Mixin(value = EntityRenderers.class, priority = 900)
public abstract class EntityRenderersMixin {
    @WrapOperation(method = "createEntityRenderers", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableMap;builder()Lcom/google/common/collect/ImmutableMap$Builder;"))
    private static ImmutableMap.Builder<EntityType<?>, EntityRenderer<?>> lightspeed$captureBuilder(
            Operation<ImmutableMap.Builder<EntityType<?>, EntityRenderer<?>>> original) {
        return new CapturingMapBuilder<>();
    }

    @WrapOperation(method = "createEntityRenderers", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private static void lightspeed$deferEntityRenderers(
            Map<EntityType<?>, EntityRendererProvider<?>> providers,
            BiConsumer<EntityType<?>, EntityRendererProvider<?>> action,
            Operation<Void> original,
            @Local ImmutableMap.Builder<EntityType<?>, EntityRenderer<?>> builder) {
        if (!DeferredRendererMap.tryDefer(providers, action, builder)) {
            original.call(providers, action);
        }
    }

    @Inject(method = "createEntityRenderers", at = @At("RETURN"), cancellable = true)
    private static void lightspeed$returnDeferredMap(
            EntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<EntityType<?>, EntityRenderer<?>>> cir,
            @Local ImmutableMap.Builder<EntityType<?>, EntityRenderer<?>> builder) {
        Map<EntityType<?>, EntityRenderer<?>> deferred = DeferredRendererMap.resolveDeferred(builder);
        if (deferred != null) {
            cir.setReturnValue(deferred);
        }
    }
}
