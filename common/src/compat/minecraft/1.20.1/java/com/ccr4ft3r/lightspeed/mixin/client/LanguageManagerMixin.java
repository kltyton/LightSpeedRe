package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.resources.AsyncLanguageReload;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ThreadLocal<AsyncLanguageReload.PreparedLanguage> lightspeed$preparedLanguage = new ThreadLocal<>();

    @Override
    public AsyncLanguageReload.PreparedLanguage lightspeed$prepareLanguage(
            ResourceManager resourceManager, String selected) {
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
        return new AsyncLanguageReload.PreparedLanguage(resourceManager, selected, extracted, List.copyOf(order),
                bidirectional, clientLanguage);
    }

    @Override
    public void lightspeed$runWithPreparedLanguage(AsyncLanguageReload.PreparedLanguage prepared, Runnable apply) {
        AsyncLanguageReload.PreparedLanguage previous = this.lightspeed$preparedLanguage.get();
        try {
            this.lightspeed$preparedLanguage.set(prepared);
            apply.run();
        } finally {
            if (previous == null) {
                this.lightspeed$preparedLanguage.remove();
            } else {
                this.lightspeed$preparedLanguage.set(previous);
            }
        }
    }

    @Unique
    private AsyncLanguageReload.PreparedLanguage lightspeed$getPreparedLanguage(ResourceManager resourceManager) {
        AsyncLanguageReload.PreparedLanguage prepared = this.lightspeed$preparedLanguage.get();
        return prepared != null && prepared.resourceManager() == resourceManager
                && prepared.expectedCode().equals(this.currentCode) ? prepared : null;
    }

    @WrapOperation(method = "onResourceManagerReload", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/language/LanguageManager;extractLanguages(Ljava/util/stream/Stream;)Ljava/util/Map;"))
    private Map<String, LanguageInfo> lightspeed$reuseExtractedLanguages(
            Stream<PackResources> packs, Operation<Map<String, LanguageInfo>> original,
            @Local(argsOnly = true) ResourceManager resourceManager) {
        AsyncLanguageReload.PreparedLanguage prepared = lightspeed$getPreparedLanguage(resourceManager);
        if (prepared != null) {
            return prepared.languages();
        }
        return original.call(packs);
    }

    @WrapOperation(method = "onResourceManagerReload", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/language/ClientLanguage;loadFrom(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Z)Lnet/minecraft/client/resources/language/ClientLanguage;"))
    private ClientLanguage lightspeed$reuseLoadedTranslations(
            ResourceManager resourceManager, List<String> order, boolean bidirectional,
            Operation<ClientLanguage> original) {
        AsyncLanguageReload.PreparedLanguage prepared = lightspeed$getPreparedLanguage(resourceManager);
        if (prepared != null && prepared.matchesOrder(order, bidirectional)) {
            return prepared.clientLanguage();
        }
        return original.call(resourceManager, order, bidirectional);
    }
}
