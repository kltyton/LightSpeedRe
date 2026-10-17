package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.platform.StartupCachePlatform;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Observes the actual reload future and preserves the native named executor when dedicated preparation is disabled. */
@Mixin(Minecraft.class)
public abstract class MinecraftReloadExecutorMixin {
    @WrapOperation(method = {"<init>", "reloadResourcePacks*"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;"))
    private ReloadInstance lightspeed$prepareReload(ReloadableResourceManager manager, Executor background,
            Executor game, CompletableFuture<?> initial, List<PackResources> packs, Operation<ReloadInstance> original) {
        ReloadInstance reload = original.call(manager, StartupCachePlatform.resourceReloadExecutor(background),
                game, initial, packs);
        StartupMetrics.observeInitialReload(reload.done());
        return reload;
    }
}
