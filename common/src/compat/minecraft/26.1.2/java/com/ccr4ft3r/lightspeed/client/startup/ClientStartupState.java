package com.ccr4ft3r.lightspeed.client.startup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/** Observes the native GUI owner after successful resource and loader completion. */
public final class ClientStartupState {
    private ClientStartupState() {
    }

    public static boolean titleReady(Minecraft client) {
        return client.screen instanceof TitleScreen
                && ClientStartupCompletion.initializationComplete();
    }

    public static boolean overlayClear(Minecraft client) {
        return client.getOverlay() == null;
    }

    public static void removeOverlay(Minecraft client) {
        client.setOverlay(null);
    }

    public static boolean isCurrentOverlay(Minecraft client, Object overlay) {
        return client.getOverlay() == overlay;
    }

    public static void finish(Minecraft client) {
        if (ClientStartupCompletion.finished() || !titleReady(client) || !overlayClear(client)) {
            return;
        }
        ClientStartupCompletion.finish();
    }
}
