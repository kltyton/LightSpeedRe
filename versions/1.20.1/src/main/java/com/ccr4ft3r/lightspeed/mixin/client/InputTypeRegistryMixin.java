package com.ccr4ft3r.lightspeed.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InputConstants.Type.class)
public abstract class InputTypeRegistryMixin {
    @Shadow @Final @Mutable private Int2ObjectMap<InputConstants.Key> map;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lightspeed$makeKeyCreationAtomic(CallbackInfo callback) {
        map = Int2ObjectMaps.synchronize(map);
    }
}
