package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.compat.ResourceReloadFailureGuard;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ProfiledReloadInstance;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin {
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void createHeadInjected(ResourceManager resourceManager, List<PreparableReloadListener> listeners,
                                           Executor backgroundExecutor, Executor gameExecutor,
                                           CompletableFuture<Unit> initialStage, boolean profiled,
                                           CallbackInfoReturnable<ReloadInstance> cir) {
        List<PreparableReloadListener> guardedListeners = ResourceReloadFailureGuard.wrap(listeners);
        if (guardedListeners == listeners) {
            return;
        }

        ReloadInstance reloadInstance = profiled
                ? new ProfiledReloadInstance(resourceManager, guardedListeners, backgroundExecutor, gameExecutor, initialStage)
                : SimpleReloadInstance.of(resourceManager, guardedListeners, backgroundExecutor, gameExecutor, initialStage);
        cir.setReturnValue(reloadInstance);
    }
}
