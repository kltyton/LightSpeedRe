package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.ExecutorService;

@Mixin(Minecraft.class)
public abstract class MinecraftReloadExecutorMixin {
    @WrapOperation(
            method = {"<init>", "reloadResourcePacks"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/Util;backgroundExecutor()Ljava/util/concurrent/ExecutorService;"
            )
    )
    private ExecutorService lightspeed$useDedicatedReloadExecutor(Operation<ExecutorService> original) {
        return GlobalCache.resourceReloadExecutor(original.call());
    }
}
