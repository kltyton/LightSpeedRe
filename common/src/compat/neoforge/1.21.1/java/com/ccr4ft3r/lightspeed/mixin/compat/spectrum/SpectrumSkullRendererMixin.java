package com.ccr4ft3r.lightspeed.mixin.compat.spectrum;

import com.ccr4ft3r.lightspeed.client.renderer.DeferredRendererMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Initializes Spectrum's skull models when its static renderer bypasses the block entity dispatcher. */
@Pseudo
@Mixin(targets = "de.dafuqs.spectrum.blocks.mob_head.client.SpectrumSkullBlockEntityRenderer", remap = false)
public abstract class SpectrumSkullRendererMixin {
    @Unique
    private static final ResourceLocation lightspeed$SKULL_RENDERER = ResourceLocation.fromNamespaceAndPath("spectrum", "skull");

    @Inject(method = "renderModels", at = @At("HEAD"), require = 1)
    private static void lightspeed$prepareSkullModels(CallbackInfo ci) {
        var renderers = Minecraft.getInstance().getBlockEntityRenderDispatcher().renderers;
        if (renderers instanceof DeferredRendererMap<?, ?, ?>) {
            BlockEntityType<?> skull = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(lightspeed$SKULL_RENDERER);
            if (skull != null) {
                // The original provider installs Spectrum's models for this resource reload.
                renderers.get(skull);
            }
        }
    }
}
