package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.ccr4ft3r.lightspeed.client.renderer.CapturingMapBuilder;
import com.ccr4ft3r.lightspeed.client.renderer.DeferredRendererMap;
import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.BiConsumer;

/** Defers block-entity renderer creation until first access while preserving all third-party lambda hooks. */
@Mixin(value = BlockEntityRenderers.class, priority = 900)
public abstract class BlockEntityRenderersMixin {
    @WrapOperation(method = "createEntityRenderers", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableMap;builder()Lcom/google/common/collect/ImmutableMap$Builder;"))
    private static ImmutableMap.Builder<BlockEntityType<?>, BlockEntityRenderer<?>> lightspeed$captureBuilder(
            Operation<ImmutableMap.Builder<BlockEntityType<?>, BlockEntityRenderer<?>>> original) {
        return new CapturingMapBuilder<>();
    }

    @WrapOperation(method = "createEntityRenderers", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private static void lightspeed$deferBlockEntityRenderers(
            Map<BlockEntityType<?>, BlockEntityRendererProvider<?>> providers,
            BiConsumer<BlockEntityType<?>, BlockEntityRendererProvider<?>> action,
            Operation<Void> original,
            @Local ImmutableMap.Builder<BlockEntityType<?>, BlockEntityRenderer<?>> builder) {
        if (!DeferredRendererMap.tryDefer(providers, action, builder)) {
            original.call(providers, action);
        }
    }

    @Inject(method = "createEntityRenderers", at = @At("RETURN"), cancellable = true)
    private static void lightspeed$returnDeferredMap(
            BlockEntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<BlockEntityType<?>, BlockEntityRenderer<?>>> cir,
            @Local ImmutableMap.Builder<BlockEntityType<?>, BlockEntityRenderer<?>> builder) {
        Map<BlockEntityType<?>, BlockEntityRenderer<?>> deferred = DeferredRendererMap.resolveDeferred(builder);
        if (deferred != null) {
            cir.setReturnValue(deferred);
        }
    }
}
