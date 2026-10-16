package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.List;

@Mixin(Minecraft.class)
public abstract class MinecraftReloadExecutorMixin {
    @WrapOperation(method = {"<init>", "reloadResourcePacks"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;"))
    private ReloadInstance lightspeed$observeInitialReload(ReloadableResourceManager manager,
            Executor background, Executor game, CompletableFuture<?> initialTask, List<PackResources> packs,
            Operation<ReloadInstance> original) {
        ReloadInstance reload = original.call(manager, background, game, initialTask, packs);
        StartupMetrics.observeInitialReload(reload.done());
        return reload;
    }

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
