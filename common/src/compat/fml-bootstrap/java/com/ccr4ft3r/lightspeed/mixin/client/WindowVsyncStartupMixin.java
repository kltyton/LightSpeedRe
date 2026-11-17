package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Suppresses VSync during startup; the client tick restores the configured value at the title
 * screen or when a direct world launch becomes active.
 */
@Mixin(Window.class)
public abstract class WindowVsyncStartupMixin {
    @ModifyVariable(method = "updateVsync", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean lightspeed$disableVsyncDuringStartup(boolean enabled) {
        return StartupMetrics.isFramePacingEnabled() && enabled;
    }
}
