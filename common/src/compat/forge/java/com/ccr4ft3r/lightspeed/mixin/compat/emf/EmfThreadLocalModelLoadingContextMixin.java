package com.ccr4ft3r.lightspeed.mixin.compat.emf;

import com.ccr4ft3r.lightspeed.client.renderer.RendererLoadingContext;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uses EMF's thread-local model selector without sharing values between loading threads. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.EMFManager", remap = false)
public abstract class EmfThreadLocalModelLoadingContextMixin implements RendererLoadingContext.Source {
    @Shadow @Final public ThreadLocal<String> currentSpecifiedModelLoading;
    @Shadow public BlockEntityType<?> currentBlockEntityTypeLoading;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lightspeed$registerModelContext(CallbackInfo ci) {
        RendererLoadingContext.setSource(this);
    }

    @Override
    public RendererLoadingContext.Snapshot lightspeed$captureModelContext() {
        return new RendererLoadingContext.Snapshot(currentSpecifiedModelLoading.get(), currentBlockEntityTypeLoading);
    }

    @Override
    public void lightspeed$restoreModelContext(RendererLoadingContext.Snapshot context) {
        currentSpecifiedModelLoading.set(context.modelName());
        currentBlockEntityTypeLoading = context.blockEntityType();
    }
}
