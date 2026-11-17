package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.startup.ImmediateOverlayClose;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = NeoForgeLoadingOverlay.class, priority = 500, remap = false)
public abstract class NeoForgeLoadingOverlayMixin implements ImmediateOverlayClose {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow @Final private DisplayWindow displayWindow;
    @Shadow @Final private ProgressMeter progressMeter;
    @Shadow private long fadeOutStart;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void lightspeed$finishReadyOverlayHead(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (lightspeed$closeIfReady()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void lightspeed$finishReadyOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        lightspeed$closeIfReady();
    }

    @Override
    public boolean lightspeed$closeIfReady() {
        if (!reload.isDone() || minecraft.getOverlay() != (Object) this
                || !(minecraft.screen instanceof TitleScreen)
                || ClientModLoader.isLoading() || ModLoader.hasErrors()
                || !StartupMetrics.isInitialReloadComplete()) {
            return false;
        }
        long remainingFadeMillis = fadeOutStart >= 0 ? Math.max(0, 2000L - (Util.getMillis() - fadeOutStart)) : 2000L;
        progressMeter.complete();
        minecraft.setOverlay(null);
        displayWindow.close();
        StartupMetrics.mark("loading-overlay-removed");
        LogUtils.getLogger().info("Lightspeed completed loading overlay: skippedFadeMillis={}", remainingFadeMillis);
        return true;
    }
}
