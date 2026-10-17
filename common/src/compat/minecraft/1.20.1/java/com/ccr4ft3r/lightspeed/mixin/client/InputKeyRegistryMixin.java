package com.ccr4ft3r.lightspeed.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(InputConstants.Key.class)
public abstract class InputKeyRegistryMixin {
    @Shadow @Final @Mutable private static Map<String, InputConstants.Key> NAME_MAP;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void lightspeed$publishKeysConcurrently(CallbackInfo callback) {
        NAME_MAP = new ConcurrentHashMap<>(NAME_MAP);
    }
}
