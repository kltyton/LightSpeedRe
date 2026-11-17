package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.resources.DeferredWorldReloadCoordinator;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BuiltInModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Awaits deferred model baking/stitching on demand if a menu UI queries ModelManager early. */
@Mixin(ModelManager.class)
public abstract class ModelManagerReadyMixin {
    @Shadow private Map<ModelResourceLocation, BakedModel> bakedRegistry;
    @Shadow private BakedModel missingModel;

    @Unique
    private static final BakedModel LIGHTSPEED$PLACEHOLDER_MODEL =
            new BuiltInModel(ItemTransforms.NO_TRANSFORMS, ItemOverrides.EMPTY, null, false);

    @Inject(method = "getModel", at = @At("HEAD"), cancellable = true)
    private void lightspeed$awaitModel(ModelResourceLocation location, CallbackInfoReturnable<BakedModel> cir) {
        if (DeferredWorldReloadCoordinator.deferImmediateModelAccess()) {
            if (this.bakedRegistry == null || this.missingModel == null) {
                cir.setReturnValue(LIGHTSPEED$PLACEHOLDER_MODEL);
            }
            return;
        }
        DeferredWorldReloadCoordinator.awaitReadyIfMainThread();
    }

    @Inject(method = "getMissingModel", at = @At("HEAD"), cancellable = true)
    private void lightspeed$awaitMissingModel(CallbackInfoReturnable<BakedModel> cir) {
        if (DeferredWorldReloadCoordinator.deferImmediateModelAccess()) {
            if (this.bakedRegistry == null || this.missingModel == null) {
                cir.setReturnValue(LIGHTSPEED$PLACEHOLDER_MODEL);
            }
            return;
        }
        DeferredWorldReloadCoordinator.awaitReadyIfMainThread();
    }

    @Inject(method = "getBlockModelShaper", at = @At("HEAD"))
    private void lightspeed$awaitBlockModelShaper(CallbackInfoReturnable<BlockModelShaper> cir) {
        if (DeferredWorldReloadCoordinator.deferImmediateModelAccess()) {
            return;
        }
        DeferredWorldReloadCoordinator.awaitReadyIfMainThread();
    }

    @Inject(method = "getAtlas", at = @At("HEAD"))
    private void lightspeed$awaitAtlas(ResourceLocation location, CallbackInfoReturnable<TextureAtlas> cir) {
        if (DeferredWorldReloadCoordinator.deferImmediateModelAccess()) {
            return;
        }
        DeferredWorldReloadCoordinator.awaitReadyIfMainThread();
    }
}
