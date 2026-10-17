package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.ccr4ft3r.lightspeed.client.renderer.LazyBlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Supplies the concrete renderer before render overrides consume its type-specific interfaces. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @ModifyArg(method = "lambda$render$0(Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;setupAndRender(Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"),
            index = 0, require = 1)
    private static BlockEntityRenderer<?> lightspeed$actualRenderer(BlockEntityRenderer<?> renderer) {
        return LazyBlockEntityRenderer.unwrap(renderer);
    }
}
