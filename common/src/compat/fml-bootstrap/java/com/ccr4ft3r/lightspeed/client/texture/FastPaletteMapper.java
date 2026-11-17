package com.ccr4ft3r.lightspeed.client.texture;

import java.util.function.IntUnaryOperator;

/**
 * Palette pixel remapper supporting direct native off-heap pixel loops without per-pixel virtual
 * interface dispatch or {@link java.nio.IntBuffer} allocations.
 */
public interface FastPaletteMapper extends IntUnaryOperator {
    void mapPixels(long srcPtr, long dstPtr, int pixelCount);
}
