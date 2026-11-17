package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.client.resources.DeferredWorldReloadCoordinator;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Splits initial client reload into menu-essential and background world-only reload batches. */
@Mixin(ReloadableResourceManager.class)
public abstract class ReloadableResourceManagerMixin {
    @Shadow @Final private PackType type;

    @Inject(method = "createReload", at = @At("HEAD"), cancellable = true)
    private void lightspeed$awaitDeferredBeforeNextReload(
            Executor backgroundExecutor,
            Executor gameExecutor,
            CompletableFuture<Unit> waitingFor,
            List<PackResources> packs,
            CallbackInfoReturnable<ReloadInstance> cir) {
        if (DeferredWorldReloadCoordinator.shouldQueueReentrantReload(this.type)) {
            List<PackResources> requestedPacks = List.copyOf(packs);
            cir.setReturnValue(DeferredWorldReloadCoordinator.queueReentrantReload(
                    gameExecutor,
                    () -> ((ReloadableResourceManager) (Object) this).createReload(
                            backgroundExecutor, gameExecutor, waitingFor, requestedPacks)));
            return;
        }
        DeferredWorldReloadCoordinator.awaitIfClientReloading(this.type);
    }

    @Redirect(
            method = "createReload",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance;create(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Z)Lnet/minecraft/server/packs/resources/ReloadInstance;"
            )
    )
    private ReloadInstance lightspeed$splitInitialClientReload(
            ResourceManager resourceManager,
            List<PreparableReloadListener> listeners,
            Executor backgroundExecutor,
            Executor gameExecutor,
            CompletableFuture<Unit> waitingFor,
            boolean logDebug) {
        return DeferredWorldReloadCoordinator.createInitialOrNormalReload(
                this.type, resourceManager, listeners, backgroundExecutor, gameExecutor, waitingFor, logDebug);
    }
}
