package com.ccr4ft3r.lightspeed.mixin.compat;

import com.ccr4ft3r.lightspeed.client.renderer.LazyBlockEntityRenderer;
import com.ccr4ft3r.lightspeed.mixin.renderer.BlockEntityRenderDispatcherAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes NetMusic's constructor-owned model before its item renderer consumes it. */
@Pseudo
@Mixin(targets = {
        "com.github.tartaricacid.netmusic.client.renderer.MusicPlayerItemRenderer",
        "com.github.tartaricacid.netmusic.client.renderer.BigMegaphoneItemRenderer"
}, remap = false)
public abstract class NetMusicItemRendererMixin {
    @Inject(method = {"renderByItem", "m_108829_"}, at = @At("HEAD"))
    private void lightspeed$initializeModelDependency(ItemStack stack, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource buffers, int light, int overlay, CallbackInfo ci) {
        var dispatcher = (BlockEntityRenderDispatcherAccessor) Minecraft.getInstance().getBlockEntityRenderDispatcher();
        var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        LazyBlockEntityRenderer.unwrap(dispatcher.lightspeed$getRenderers().get(type));
    }
}
