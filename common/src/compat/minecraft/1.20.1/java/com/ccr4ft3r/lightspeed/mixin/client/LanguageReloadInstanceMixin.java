package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.resources.AsyncLanguageReload;
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
        if (!(listener instanceof LanguageManager languageManager)
                || !(listener instanceof AsyncLanguageReload.PreparedHost host)) {
            return original.call(stateFactory, barrier, resourceManager, listener, backgroundExecutor, gameExecutor);
        }
        String selectedCode = languageManager.getSelected();
        CompletableFuture<AsyncLanguageReload.PreparedLanguage> preparedFuture = CompletableFuture.supplyAsync(
                () -> host.lightspeed$prepareLanguage(resourceManager, selectedCode), backgroundExecutor);
        PreparableReloadListener.PreparationBarrier gatedBarrier = new PreparableReloadListener.PreparationBarrier() {
            @Override
            public <T> CompletableFuture<T> wait(T preparation) {
                return preparedFuture.thenCompose(ignored -> barrier.wait(preparation));
            }
        };
        Executor languageApplyExecutor = task -> gameExecutor.execute(() -> {
            AsyncLanguageReload.PreparedLanguage prepared = preparedFuture.isCompletedExceptionally()
                    ? null : preparedFuture.getNow(null);
            host.lightspeed$runWithPreparedLanguage(prepared, task);
        });
        return original.call(stateFactory, gatedBarrier, resourceManager, listener, backgroundExecutor, languageApplyExecutor);
    }
}
