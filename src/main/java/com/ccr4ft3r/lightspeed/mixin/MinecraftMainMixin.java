package com.ccr4ft3r.lightspeed.mixin;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
public class MinecraftMainMixin {

    @Inject(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/SharedConstants;tryDetectVersion()V", shift = At.Shift.AFTER))
    private static void mainTryDetecVersionInjected(String[] args, CallbackInfo ci) {
        GlobalCache.loadPersistedCachesAsync();
    }
}
