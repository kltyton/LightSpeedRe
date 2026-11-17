package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.resources.AsyncLanguageReload;
import com.ccr4ft3r.lightspeed.client.resources.DeferredWorldReloadCoordinator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(SimpleReloadInstance.class)
public abstract class LanguageReloadInstanceMixin {
    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture<?> lightspeed$prepareLanguageAsync(
            @Coerce Object stateFactory,
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            PreparableReloadListener listener,
            Executor backgroundExecutor,
            Executor gameExecutor,
            Operation<CompletableFuture<?>> original) {
        PreparableReloadListener actualListener = DeferredWorldReloadCoordinator.unwrapImmediateListener(listener);
        if (!(actualListener instanceof LanguageManager) || !(actualListener instanceof AsyncLanguageReload.PreparedHost host)) {
            return original.call(stateFactory, barrier, resourceManager, listener, backgroundExecutor, gameExecutor);
        }
        CompletableFuture<AsyncLanguageReload.PreparedLanguage> preparedFuture = CompletableFuture.supplyAsync(
                () -> host.lightspeed$prepareLanguage(resourceManager),
                DeferredWorldReloadCoordinator.wrapImmediateExecutor(actualListener, backgroundExecutor));
        host.lightspeed$beginAsyncPrepare(resourceManager, preparedFuture);
        PreparableReloadListener.PreparationBarrier gatedBarrier = new PreparableReloadListener.PreparationBarrier() {
            @Override
            public <T> CompletableFuture<T> wait(T preparation) {
                return preparedFuture.thenCompose(ignored -> barrier.wait(preparation));
            }
        };
        return original.call(stateFactory, gatedBarrier, resourceManager, listener, backgroundExecutor, gameExecutor);
    }
}
