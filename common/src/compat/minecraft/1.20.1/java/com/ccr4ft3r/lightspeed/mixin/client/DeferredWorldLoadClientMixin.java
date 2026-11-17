package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.resources.DeferredWorldReloadCoordinator;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Ensures background world-only resources complete before singleplayer launch or manual reloads. */
@Mixin(Minecraft.class)
public abstract class DeferredWorldLoadClientMixin {
    @Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("HEAD"))
    private void lightspeed$awaitDeferredBeforeClientLevel(ClientLevel level, CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (level != null && minecraft.isSameThread()) {
            DeferredWorldReloadCoordinator.awaitReady(minecraft);
        }
    }

    @Inject(method = "doWorldLoad", at = @At("HEAD"))
    private void lightspeed$awaitDeferredBeforeWorldLoad(
            String levelId,
            LevelStorageSource.LevelStorageAccess levelStorage,
            PackRepository packRepository,
            WorldStem worldStem,
            boolean newWorld,
            CallbackInfo ci) {
        DeferredWorldReloadCoordinator.awaitReady((Minecraft) (Object) this);
    }

    @Inject(method = "reloadResourcePacks(Z)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void lightspeed$awaitDeferredBeforeManualReload(
            boolean errorOnly,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        DeferredWorldReloadCoordinator.awaitReady((Minecraft) (Object) this);
    }

    @Inject(method = "setOverlay", at = @At("RETURN"))
    private void lightspeed$startDeferredWhenOverlayClears(Overlay overlay, CallbackInfo ci) {
        if (overlay == null && StartupMetrics.hasRecorded("title-screen-operable")) {
            DeferredWorldReloadCoordinator.startIfPending();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void lightspeed$awaitDeferredBeforeShutdown(CallbackInfo ci) {
        DeferredWorldReloadCoordinator.awaitOnShutdown((Minecraft) (Object) this);
    }
}
