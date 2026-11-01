package com.ccr4ft3r.lightspeed.client.renderer;

import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

/** Carries model selection from the factory callback to deferred renderer construction. */
public final class RendererLoadingContext {
    private static volatile Source source;

    private RendererLoadingContext() { }

    public static void setSource(Source modelContext) {
        source = modelContext;
    }

    public static Snapshot capture() {
        Source current = source;
        return current == null ? null : current.lightspeed$captureModelContext();
    }

    public static <T> T create(Snapshot context, Supplier<T> constructor) {
        if (context == null) {
            return constructor.get();
        }
        Source current = source;
        Snapshot previous = current.lightspeed$captureModelContext();
        current.lightspeed$restoreModelContext(context);
        try {
            return constructor.get();
        } finally {
            current.lightspeed$restoreModelContext(previous);
        }
    }

    public record Snapshot(String modelName, BlockEntityType<?> blockEntityType) { }

    public interface Source {
        Snapshot lightspeed$captureModelContext();

        void lightspeed$restoreModelContext(Snapshot context);
    }
}
