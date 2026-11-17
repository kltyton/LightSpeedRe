package com.ccr4ft3r.lightspeed.client.startup;

/** Allows TitleScreen ticks and overlay render hooks to dismiss a finished startup overlay immediately. */
public interface ImmediateOverlayClose {
    boolean lightspeed$closeIfReady();
}
