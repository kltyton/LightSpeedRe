package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.resources.AsyncLanguageReload;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@Mixin(LanguageManager.class)
public abstract class LanguageManagerMixin implements AsyncLanguageReload.PreparedHost {
    @Shadow @Final private static LanguageInfo DEFAULT_LANGUAGE;
    @Shadow private String currentCode;

    @Shadow
    private static Map<String, LanguageInfo> extractLanguages(Stream<PackResources> packs) {
        throw new AssertionError();
    }

    @Unique
    private volatile CompletableFuture<AsyncLanguageReload.PreparedLanguage> lightspeed$pendingPrepared;
    @Unique
    private AsyncLanguageReload.PreparedLanguage lightspeed$activePrepared;

    @Override
    public void lightspeed$beginAsyncPrepare(ResourceManager resourceManager,
                                             CompletableFuture<AsyncLanguageReload.PreparedLanguage> preparedFuture) {
        this.lightspeed$pendingPrepared = preparedFuture;
    }

    @Override
    public CompletableFuture<AsyncLanguageReload.PreparedLanguage> lightspeed$consumePreparedFuture() {
        CompletableFuture<AsyncLanguageReload.PreparedLanguage> pending = this.lightspeed$pendingPrepared;
        this.lightspeed$pendingPrepared = null;
        return pending;
    }

    @Override
    public AsyncLanguageReload.PreparedLanguage lightspeed$prepareLanguage(ResourceManager resourceManager) {
        String selected = this.currentCode;
        Map<String, LanguageInfo> extracted = extractLanguages(resourceManager.listPacks());
        List<String> order = new ArrayList<>(2);
        boolean bidirectional = DEFAULT_LANGUAGE.bidirectional();
        order.add(LanguageManager.DEFAULT_LANGUAGE_CODE);
        if (!LanguageManager.DEFAULT_LANGUAGE_CODE.equals(selected)) {
            LanguageInfo info = extracted.get(selected);
            if (info != null) {
                order.add(selected);
                bidirectional = info.bidirectional();
            }
        }
        ClientLanguage clientLanguage = ClientLanguage.loadFrom(resourceManager, order, bidirectional);
        return new AsyncLanguageReload.PreparedLanguage(selected, extracted, List.copyOf(order),
                bidirectional, clientLanguage);
    }

    @Override
    public void lightspeed$setActivePrepared(AsyncLanguageReload.PreparedLanguage prepared) {
        this.lightspeed$activePrepared = prepared;
    }

    @Override
    public AsyncLanguageReload.PreparedLanguage lightspeed$activePrepared() {
        return this.lightspeed$activePrepared;
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void lightspeed$attachPreparedLanguage(ResourceManager resourceManager, CallbackInfo ci) {
        CompletableFuture<AsyncLanguageReload.PreparedLanguage> future = this.lightspeed$pendingPrepared;
        if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
            this.lightspeed$pendingPrepared = null;
            AsyncLanguageReload.PreparedLanguage data = future.getNow(null);
            if (data != null && data.expectedCode().equals(this.currentCode)) {
                this.lightspeed$activePrepared = data;
            }
        }
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void lightspeed$clearPreparedLanguage(ResourceManager resourceManager, CallbackInfo ci) {
        this.lightspeed$activePrepared = null;
    }

    @WrapOperation(method = "onResourceManagerReload", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/language/LanguageManager;extractLanguages(Ljava/util/stream/Stream;)Ljava/util/Map;"))
    private Map<String, LanguageInfo> lightspeed$reuseExtractedLanguages(
            Stream<PackResources> packs, Operation<Map<String, LanguageInfo>> original) {
        AsyncLanguageReload.PreparedLanguage prepared = this.lightspeed$activePrepared;
        if (prepared != null && prepared.expectedCode().equals(this.currentCode)) {
            return prepared.languages();
        }
        return original.call(packs);
    }

    @WrapOperation(method = "onResourceManagerReload", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/language/ClientLanguage;loadFrom(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Z)Lnet/minecraft/client/resources/language/ClientLanguage;"))
    private ClientLanguage lightspeed$reuseLoadedTranslations(
            ResourceManager resourceManager, List<String> order, boolean bidirectional,
            Operation<ClientLanguage> original) {
        AsyncLanguageReload.PreparedLanguage prepared = this.lightspeed$activePrepared;
        if (prepared != null && prepared.matchesOrder(order, bidirectional)) {
            return prepared.clientLanguage();
        }
        return original.call(resourceManager, order, bidirectional);
    }
}
