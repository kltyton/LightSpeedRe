package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips idle frame-rate sleeps during startup until the title is operable or a world is entered.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemStartupMixin {
    @Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
    private static void lightspeed$skipStartupFrameSleep(int framerateLimit, CallbackInfo ci) {
        if (!StartupMetrics.isFramePacingEnabled()) {
            ci.cancel();
        }
    }
}
