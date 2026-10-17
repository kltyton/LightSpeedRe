package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.compat.ResourceReloadFailureGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Preserves shared preparation state and listener identity when explicit failure isolation is configured. */
@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin {
    @WrapOperation(method = "prepareTasks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<?> lightspeed$guardListener(@Coerce Object factory,
            PreparableReloadListener.SharedState shared, PreparableReloadListener.PreparationBarrier barrier,
            PreparableReloadListener listener, Executor preparation, Executor application,
            Operation<CompletableFuture<?>> original) {
        if (!ResourceReloadFailureGuard.shouldIsolate(listener)) {
            return original.call(factory, shared, barrier, listener, preparation, application);
        }
        return ResourceReloadFailureGuard.guard(listener,
                () -> original.call(factory, shared, barrier, listener, preparation, application));
    }
}
