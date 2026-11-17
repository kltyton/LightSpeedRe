package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.client.resources.PreparedLanguageReload;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

@Mixin(LanguageManager.class)
public abstract class LanguageManagerMixin implements ResourceManagerReloadListener {

    @Unique
    private static final ThreadLocal<PreparedLanguageReload> LIGHTSPEED$ACTIVE_PREPARED = new ThreadLocal<>();

    @Shadow
    @Final
    private static LanguageInfo DEFAULT_LANGUAGE;

    @Shadow
    private String currentCode;

    @Shadow
    private static Map<String, LanguageInfo> extractLanguages(Stream<PackResources> packs) {
        throw new AssertionError();
    }

    @Shadow
    public abstract void onResourceManagerReload(ResourceManager resourceManager);

    @Override
    public CompletableFuture<Void> reload(
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            ProfilerFiller preparationsProfiler,
            ProfilerFiller reloadProfiler,
            Executor backgroundExecutor,
            Executor gameExecutor) {
        if (!GlobalCache.isEnabled) {
            return barrier.wait(Unit.INSTANCE).thenRunAsync(() -> {
                reloadProfiler.startTick();
                reloadProfiler.push("listener");
                this.onResourceManagerReload(resourceManager);
                reloadProfiler.pop();
                reloadProfiler.endTick();
            }, gameExecutor);
        }

        String selected = this.currentCode;
        return CompletableFuture.supplyAsync(() -> {
            Map<String, LanguageInfo> discovered = extractLanguages(resourceManager.listPacks());
            List<String> loadOrder = new ArrayList<>(2);
            boolean bidirectional = DEFAULT_LANGUAGE.bidirectional();
            loadOrder.add("en_us");
            if (selected != null && !"en_us".equals(selected)) {
                LanguageInfo info = discovered.get(selected);
                if (info != null) {
                    loadOrder.add(selected);
                    bidirectional = info.bidirectional();
                }
            }
            ClientLanguage clientLanguage = ClientLanguage.loadFrom(resourceManager, loadOrder, bidirectional);
            return new PreparedLanguageReload(
                    selected, discovered, loadOrder, bidirectional, clientLanguage);
        }, backgroundExecutor).thenCompose(barrier::wait).thenAcceptAsync(prepared -> {
            reloadProfiler.startTick();
            reloadProfiler.push("listener");
            PreparedLanguageReload previous = LIGHTSPEED$ACTIVE_PREPARED.get();
            if (Objects.equals(prepared.selectedCode(), this.currentCode)) {
                LIGHTSPEED$ACTIVE_PREPARED.set(prepared);
            } else {
                LIGHTSPEED$ACTIVE_PREPARED.remove();
            }
            try {
                this.onResourceManagerReload(resourceManager);
            } finally {
                if (previous == null) {
                    LIGHTSPEED$ACTIVE_PREPARED.remove();
                } else {
                    LIGHTSPEED$ACTIVE_PREPARED.set(previous);
                }
                reloadProfiler.pop();
                reloadProfiler.endTick();
            }
        }, gameExecutor);
    }

    @Redirect(
            method = "onResourceManagerReload",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/language/LanguageManager;extractLanguages(Ljava/util/stream/Stream;)Ljava/util/Map;"
            )
    )
    private static Map<String, LanguageInfo> lightspeed$reuseExtractedLanguages(Stream<PackResources> packs) {
        PreparedLanguageReload prepared = LIGHTSPEED$ACTIVE_PREPARED.get();
        if (prepared != null) {
            return prepared.languages();
        }
        return extractLanguages(packs);
    }

    @Redirect(
            method = "onResourceManagerReload",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/language/ClientLanguage;loadFrom(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Z)Lnet/minecraft/client/resources/language/ClientLanguage;"
            )
    )
    private static ClientLanguage lightspeed$reuseClientLanguage(
            ResourceManager resourceManager, List<String> filenames, boolean defaultRightToLeft) {
        PreparedLanguageReload prepared = LIGHTSPEED$ACTIVE_PREPARED.get();
        if (prepared != null
                && prepared.bidirectional() == defaultRightToLeft
                && prepared.loadOrder().equals(filenames)) {
            return prepared.clientLanguage();
        }
        return ClientLanguage.loadFrom(resourceManager, filenames, defaultRightToLeft);
    }
}
