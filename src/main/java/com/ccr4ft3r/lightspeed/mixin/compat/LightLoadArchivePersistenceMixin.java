package com.ccr4ft3r.lightspeed.mixin.compat;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps completed class-byte archive compression and publication off the render thread. */
@Pseudo
@Mixin(targets = "com.lightload.core.StartupCoordinator", remap = false)
public abstract class LightLoadArchivePersistenceMixin {
    @WrapOperation(method = "startupCompleted", at = @At(value = "INVOKE",
            target = "Lcom/lightload/core/StartupCoordinator;finishEarlyAgentClassCache()V"), remap = false)
    private static void lightspeed$persistArchiveAfterPreparation(Operation<Void> original) {
        GlobalCache.persistCacheAsync("LightLoad class archive", () -> original.call());
    }
}
