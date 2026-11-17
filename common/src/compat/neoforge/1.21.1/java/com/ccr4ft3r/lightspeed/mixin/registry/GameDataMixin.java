package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.compat.ModPackPrewarmSupport;
import net.neoforged.neoforge.registries.GameData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prewarms per-mod ZIP central-directory pack indexes before client resource packs are constructed. */
@Mixin(value = GameData.class, remap = false)
public abstract class GameDataMixin {
    @Inject(method = "postRegisterEvents", at = @At("HEAD"), require = 0)
    private static void lightspeed$prewarmModPacksEarly(CallbackInfo ci) {
        ModPackPrewarmSupport.startAsyncPrewarm();
    }
}
