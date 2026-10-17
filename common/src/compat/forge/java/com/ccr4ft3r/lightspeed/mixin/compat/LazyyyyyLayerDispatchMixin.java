package com.ccr4ft3r.lightspeed.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.ModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Layer callbacks may allocate GL resources even when Lazyyyyy constructs renderers asynchronously. */
@Pseudo
@Mixin(targets = {
        "settingdust.lazyyyyy.forge.minecraft.LazyEntityRenderersForge$1$1$1",
        "settingdust.lazyyyyy.forge.minecraft.LazyEntityRenderersForge$1$2$1"
}, remap = false, priority = 2000)
public abstract class LazyyyyyLayerDispatchMixin {
    @WrapOperation(method = "emit", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/fml/ModLoader;postEvent(Lnet/minecraftforge/eventbus/api/Event;)V"),
            remap = false)
    private void lightspeed$dispatchLayers(ModLoader loader, Event event, Operation<Void> original) {
        if (RenderSystem.isOnRenderThread()) {
            original.call(loader, event);
        } else {
            // A layer callback can await another renderer emitted by the same coroutine flow.
            Minecraft.getInstance().execute(() -> original.call(loader, event));
        }
    }
}
