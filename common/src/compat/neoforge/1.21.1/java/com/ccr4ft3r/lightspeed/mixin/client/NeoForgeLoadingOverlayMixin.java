package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.neoforge.client.loading.ClientModLoader;
import net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Consumer;

@Mixin(value = NeoForgeLoadingOverlay.class, remap = false)
public abstract class NeoForgeLoadingOverlayMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow @Final private DisplayWindow displayWindow;
    @Shadow @Final private ProgressMeter progressMeter;
    @Shadow private long fadeOutStart;
    @Unique private boolean lightspeed$reloadSucceeded;

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void lightspeed$recordCompletion(Consumer<Object> callback, Object outcome, Operation<Void> original) {
        original.call(callback, outcome);
        lightspeed$reloadSucceeded = ((Optional<?>) outcome).isEmpty();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void lightspeed$finishReadyOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (!lightspeed$reloadSucceeded || fadeOutStart < 0 || !reload.isDone()
                || minecraft.getOverlay() != (Object) this || !(minecraft.screen instanceof TitleScreen)
                || ClientModLoader.isLoading() || ModLoader.hasErrors()
                || !StartupMetrics.isInitialReloadComplete()) return;
        long remainingFadeMillis = Math.max(0, 2000L - (Util.getMillis() - fadeOutStart));
        progressMeter.complete();
        minecraft.setOverlay(null);
        displayWindow.close();
        StartupMetrics.mark("loading-overlay-removed");
        LogUtils.getLogger().info("Lightspeed completed loading overlay: skippedFadeMillis={}", remainingFadeMillis);
    }
}
