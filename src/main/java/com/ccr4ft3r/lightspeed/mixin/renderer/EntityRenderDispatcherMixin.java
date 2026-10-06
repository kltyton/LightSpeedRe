package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.ccr4ft3r.lightspeed.client.renderer.LazyEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "getRenderer", at = @At("RETURN"), cancellable = true)
    @SuppressWarnings("unchecked")
    private <T extends Entity> void lightspeed$resolveLazyRenderer(
            T entity, CallbackInfoReturnable<EntityRenderer<? super T>> cir) {
        if (cir.getReturnValue() instanceof LazyEntityRenderer<?>) {
            cir.setReturnValue((EntityRenderer<? super T>) LazyEntityRenderer.unwrap(cir.getReturnValue()));
        }
    }
}
