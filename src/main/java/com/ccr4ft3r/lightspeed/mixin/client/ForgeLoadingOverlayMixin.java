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
import net.minecraftforge.client.loading.ClientModLoader;
import net.minecraftforge.client.loading.ForgeLoadingOverlay;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.earlydisplay.DisplayWindow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Consumer;

@Mixin(value = ForgeLoadingOverlay.class, remap = false)
public abstract class ForgeLoadingOverlayMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow @Final private DisplayWindow displayWindow;
    @Shadow private long fadeOutStart;
    @Unique private boolean lightspeed$reloadSucceeded;

    @WrapOperation(method = {"render", "m_88315_"}, at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void lightspeed$recordCompletion(Consumer<Object> callback, Object outcome, Operation<Void> original) {
        original.call(callback, outcome);
        lightspeed$reloadSucceeded = ((Optional<?>) outcome).isEmpty();
    }

    @Inject(method = {"render", "m_88315_"}, at = @At("TAIL"))
    private void lightspeed$finishReadyOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (!lightspeed$reloadSucceeded || fadeOutStart < 0 || !reload.isDone()
                || minecraft.getOverlay() != (Object) this || !(minecraft.screen instanceof TitleScreen)
                || ClientModLoader.isLoading() || !ModLoader.isLoadingStateValid()
                || !StartupMetrics.isInitialReloadComplete()) return;
        long remainingFadeMillis = Math.max(0, 2000L - (Util.getMillis() - fadeOutStart));
        minecraft.setOverlay(null);
        displayWindow.close();
        StartupMetrics.mark("loading-overlay-removed");
        LogUtils.getLogger().info("Lightspeed completed loading overlay: skippedFadeMillis={}", remainingFadeMillis);
    }
}
