package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.ModelInputSnapshot;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(ModelManager.class)
public abstract class ModelManagerInputSnapshotMixin {
    @Inject(method = "loadBlockModels", at = @At("HEAD"), cancellable = true)
    private static void lightspeed$loadModelSnapshot(ResourceManager resources, Executor executor,
            CallbackInfoReturnable<CompletableFuture<Map<ResourceLocation, BlockModel>>> cir) {
        if (ModelInputSnapshot.enabled()) {
            cir.setReturnValue(ModelInputSnapshot.loadBlockModels(resources, executor));
        }
    }

    @Inject(method = "loadBlockStates", at = @At("HEAD"), cancellable = true)
    private static void lightspeed$loadBlockstateSnapshot(ResourceManager resources, Executor executor,
            CallbackInfoReturnable<CompletableFuture<Map<ResourceLocation, List<ModelBakery.LoadedJson>>>> cir) {
        if (ModelInputSnapshot.enabled()) {
            cir.setReturnValue(ModelInputSnapshot.loadBlockStates(resources, executor));
        }
    }
}
