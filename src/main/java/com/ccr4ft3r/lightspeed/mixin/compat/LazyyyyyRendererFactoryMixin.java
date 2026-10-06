package com.ccr4ft3r.lightspeed.mixin.compat;

import com.ccr4ft3r.lightspeed.client.renderer.LazyBlockEntityRenderer;
import com.ccr4ft3r.lightspeed.client.renderer.LazyEntityRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Resolves our delegate when Lazyyyyy first constructs a renderer for its accessor and layer callbacks. */
@Pseudo
@Mixin(targets = {
        "settingdust.lazyyyyy.minecraft.lazy_entity_renderers.LazyPlayerRenderer$loading$1",
        "settingdust.lazyyyyy.minecraft.lazy_entity_renderers.LazyEntityRenderer$loading$1",
        "settingdust.lazyyyyy.minecraft.lazy_entity_renderers.LazyBlockEntityRenderer$loading$1"
}, remap = false, priority = 2000)
public abstract class LazyyyyyRendererFactoryMixin {
    @WrapOperation(method = "invokeSuspend", at = @At(value = "INVOKE",
            target = "Lkotlin/jvm/functions/Function0;invoke()Ljava/lang/Object;"), remap = false)
    private Object lightspeed$resolveCreatedRenderer(@Coerce Object factory, Operation<Object> original) {
        Object renderer = original.call(factory);
        if (renderer instanceof EntityRenderer<?> entityRenderer) return LazyEntityRenderer.unwrap(entityRenderer);
        if (renderer instanceof BlockEntityRenderer<?> blockRenderer) return LazyBlockEntityRenderer.unwrap(blockRenderer);
        return renderer;
    }
}
