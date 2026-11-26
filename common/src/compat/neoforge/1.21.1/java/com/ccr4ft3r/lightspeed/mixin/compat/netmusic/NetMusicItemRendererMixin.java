package com.ccr4ft3r.lightspeed.mixin.compat.netmusic;

import com.ccr4ft3r.lightspeed.client.renderer.DeferredRendererMap;
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

/** Resolves the current NetMusic block-entity renderer before the matching item renderer uses it. */
@Pseudo
@Mixin(targets = {
        "com.github.tartaricacid.netmusic.client.renderer.MusicPlayerItemRenderer",
        "com.github.tartaricacid.netmusic.client.renderer.BigMegaphoneItemRenderer"
}, remap = false)
public abstract class NetMusicItemRendererMixin {
    @Inject(method = "renderByItem", at = @At("HEAD"), require = 1)
    private void lightspeed$resolveBlockEntityRenderer(ItemStack stack, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource buffers, int light, int overlay, CallbackInfo info) {
        var renderers = Minecraft.getInstance().getBlockEntityRenderDispatcher().renderers;
        if (renderers instanceof DeferredRendererMap<?, ?, ?>) {
            var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (type != null) {
                renderers.get(type);
            }
        }
    }
}
