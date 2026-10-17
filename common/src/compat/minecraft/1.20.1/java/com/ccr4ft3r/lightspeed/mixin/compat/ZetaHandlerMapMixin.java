package com.ccr4ft3r.lightspeed.mixin.compat;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Map;

/** Protects the shared handler map while independent listener preparation remains parallel. */
@Pseudo
@Mixin(targets = "org.violetmoon.zetaimplforge.event.ForgeZetaEventBus", remap = false)
public abstract class ZetaHandlerMapMixin {
    @Shadow @Final @Mutable private Map<Object, Object> convertedHandlers;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void lightspeed$protectHandlerMap(CallbackInfo callback) {
        convertedHandlers = Collections.synchronizedMap(convertedHandlers);
    }
}
