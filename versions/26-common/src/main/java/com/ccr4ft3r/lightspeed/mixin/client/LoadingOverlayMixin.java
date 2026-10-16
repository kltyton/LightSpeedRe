package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.client.startup.ClientStartupState;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Consumer;

/** Removes the initial fade only after the native completion callback and resource load both succeed. */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow private long fadeOutStart;
    @Unique private boolean lightspeed$completed;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void lightspeed$recordCompletion(Consumer<Object> callback, Object result, Operation<Void> original) {
        original.call(callback, result);
        lightspeed$completed = ((Optional<?>) result).isEmpty();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void lightspeed$finishInitialFade(CallbackInfo ci) {
        if (GlobalCache.isEnabled && lightspeed$completed && fadeOutStart >= 0 && reload.isDone()
                && ClientStartupState.isCurrentOverlay(minecraft, this)
                && ClientStartupState.titleReady(minecraft)) {
            ClientStartupState.removeOverlay(minecraft);
            StartupMetrics.mark("loading-overlay-removed");
        }
    }
}
