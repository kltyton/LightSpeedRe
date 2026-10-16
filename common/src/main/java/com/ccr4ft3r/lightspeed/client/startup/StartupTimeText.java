package com.ccr4ft3r.lightspeed.client.startup;

import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Formats the frozen JVM-to-menu duration for the current client. */
public final class StartupTimeText {
    private static Component text;

    private StartupTimeText() {
    }

    public static Component get() {
        long millis = StartupMetrics.completedStartupMillis();
        if (text == null && millis >= 0) {
            text = Component.translatable("lightspeed.startup_time", String.format(Locale.ROOT, "%.3f", millis / 1000.0));
        }
        return text;
    }
}
