package com.ccr4ft3r.lightspeed.mixin.compat;

import com.ccr4ft3r.lightspeed.client.renderer.LazyBlockEntityRenderer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;

/** Provides the concrete pylon renderer where the item renderer consumes its specific type. */
@Pseudo
@Mixin(targets = "mythicbotany.pylon.RenderAlfsteelPylon$ItemRenderer", remap = false)
public abstract class MythicBotanyPylonItemRendererMixin {
    @Group(name = "lightspeed$pylonRenderer", min = 1, max = 1)
    @ModifyExpressionValue(method = {"renderByItem", "m_108829_"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;getRenderer(Lnet/minecraft/world/level/block/entity/BlockEntity;)Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;"),
            remap = false, require = 0, allow = 1)
    private BlockEntityRenderer<?> lightspeed$unwrapNamedPylonRenderer(BlockEntityRenderer<?> renderer) {
        return LazyBlockEntityRenderer.unwrap(renderer);
    }

    @Group(name = "lightspeed$pylonRenderer", min = 1, max = 1)
    @ModifyExpressionValue(method = {"renderByItem", "m_108829_"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;m_112265_(Lnet/minecraft/world/level/block/entity/BlockEntity;)Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;"),
            remap = false, require = 0, allow = 1)
    private BlockEntityRenderer<?> lightspeed$unwrapSrgPylonRenderer(BlockEntityRenderer<?> renderer) {
        return LazyBlockEntityRenderer.unwrap(renderer);
    }
}
