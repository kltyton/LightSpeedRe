package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.resources.DeferredWorldReloadCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ensures background world-only resources complete before connecting to a multiplayer server. */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenDeferredMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void lightspeed$awaitDeferredBeforeConnect(
            Screen parent,
            Minecraft minecraft,
            ServerAddress address,
            ServerData serverData,
            boolean isQuickPlay,
            TransferState transferState,
            CallbackInfo ci) {
        DeferredWorldReloadCoordinator.awaitReady(minecraft);
    }
}
