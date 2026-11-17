package com.ccr4ft3r.lightspeed.client.renderer;

import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

/** Preserves third-party model loading context across deferred renderer construction. */
public final class RendererLoadingContext {
    private static volatile Source source;

    private RendererLoadingContext() {}

    public static void setSource(Source modelContext) {
        source = modelContext;
    }

    public static Snapshot capture() {
        Source current = source;
        return current == null ? null : current.lightspeed$captureModelContext();
    }

    public static void runPreserving(Snapshot baseline, Runnable action) {
        Source current = source;
        if (current == null || baseline == null) {
            action.run();
            return;
        }
        Snapshot previous = current.lightspeed$captureModelContext();
        current.lightspeed$restoreModelContext(baseline);
        try {
            action.run();
        } finally {
            current.lightspeed$restoreModelContext(previous);
        }
    }

    public static <T> T create(Snapshot context, Supplier<T> constructor) {
        Source current = source;
        if (context == null || current == null) {
            return constructor.get();
        }
        Snapshot previous = current.lightspeed$captureModelContext();
        current.lightspeed$restoreModelContext(context);
        try {
            return constructor.get();
        } finally {
            current.lightspeed$restoreModelContext(previous);
        }
    }

    public record Snapshot(String modelName, BlockEntityType<?> blockEntityType) {}

    public interface Source {
        Snapshot lightspeed$captureModelContext();

        void lightspeed$restoreModelContext(Snapshot context);
    }
}
