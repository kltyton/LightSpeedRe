package com.ccr4ft3r.lightspeed.mixin.compat;

import com.ccr4ft3r.lightspeed.client.renderer.LazyBlockEntityRenderer;
import com.ccr4ft3r.lightspeed.mixin.renderer.BlockEntityRenderDispatcherAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Resolves constructor-owned model state when sign geometry first consumes it. */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.supplementaries.client.renderers.tiles.SignPostBlockTileRenderer", remap = false)
public abstract class SupplementariesSignModelMixin {
    @Unique
    private static final ResourceLocation lightspeed$signpost =
            new ResourceLocation("supplementaries", "sign_post");

    @Inject(method = "renderSign", at = @At("HEAD"))
    private static void lightspeed$initializeModelDependency(CallbackInfo ci) {
        var dispatcher = (BlockEntityRenderDispatcherAccessor) Minecraft.getInstance().getBlockEntityRenderDispatcher();
        var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(lightspeed$signpost);
        var renderer = dispatcher.lightspeed$getRenderers().get(type);
        if (renderer instanceof LazyBlockEntityRenderer<?> lazy) {
            LazyBlockEntityRenderer.unwrap(lazy);
        }
    }
}
