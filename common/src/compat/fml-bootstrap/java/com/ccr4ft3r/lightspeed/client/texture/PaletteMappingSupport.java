package com.ccr4ft3r.lightspeed.client.texture;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/**
 * Replaces vanilla {@code Int2IntOpenHashMap} palette remappers with compact primitive linear lookup
 * for small palettes and off-heap direct pixel batch loops paired with single-pixel run caching.
 */
public final class PaletteMappingSupport {
    private static final int LINEAR_CUTOFF = 24;

    private PaletteMappingSupport() {
    }

    public static IntUnaryOperator createPaletteMapping(int[] sourcePalette, int[] targetPalette, Logger logger) {
        if (targetPalette.length != sourcePalette.length) {
            logger.warn("Palette mapping has different sizes: {} and {}", sourcePalette.length, targetPalette.length);
            throw new IllegalArgumentException();
        }
        int len = sourcePalette.length;
        int[] keys = new int[len];
        int[] vals = new int[len];
        int count = 0;
        for (int i = 0; i < len; i++) {
            int src = sourcePalette[i];
            if ((src >>> 24) == 0) {
                continue;
            }
            int rgb = src & 0x00FFFFFF;
            int dst = targetPalette[i];
            boolean replaced = false;
            for (int k = 0; k < count; k++) {
                if (keys[k] == rgb) {
                    vals[k] = dst;
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                keys[count] = rgb;
                vals[count] = dst;
                count++;
                if (count > LINEAR_CUTOFF) {
                    return buildMapMapper(sourcePalette, targetPalette);
                }
            }
        }
        return new SmallPaletteMapper(Arrays.copyOf(keys, count), Arrays.copyOf(vals, count));
    }

    private static FastPaletteMapper buildMapMapper(int[] sourcePalette, int[] targetPalette) {
        Int2IntOpenHashMap map = new Int2IntOpenHashMap(targetPalette.length);
        for (int i = 0; i < sourcePalette.length; i++) {
            int src = sourcePalette[i];
            if ((src >>> 24) != 0) {
                map.put(src & 0x00FFFFFF, targetPalette[i]);
            }
        }
        return new MapPaletteMapper(map);
    }

    private static int blendAlpha(int alpha, int mapped) {
        return (((alpha * (mapped >>> 24)) / 255) << 24) | (mapped & 0x00FFFFFF);
    }

    private static final class SmallPaletteMapper implements FastPaletteMapper {
        private final int[] keys;
        private final int[] values;

        private SmallPaletteMapper(int[] keys, int[] values) {
            this.keys = keys;
            this.values = values;
        }

        @Override
        public int applyAsInt(int pixel) {
            int alpha = pixel >>> 24;
            if (alpha == 0) {
                return pixel;
            }
            int rgb = pixel & 0x00FFFFFF;
            int mapped = rgb | 0xFF000000;
            int[] k = this.keys;
            for (int i = 0, n = k.length; i < n; i++) {
                if (k[i] == rgb) {
                    mapped = this.values[i];
                    break;
                }
            }
            return blendAlpha(alpha, mapped);
        }

        @Override
        public void mapPixels(long srcPtr, long dstPtr, int pixelCount) {
            int[] k = this.keys;
            int[] v = this.values;
            int mapLen = k.length;
            int prevIn = 0;
            int prevOut = 0;
            for (int i = 0; i < pixelCount; i++) {
                long off = (long) i << 2;
                int pixel = MemoryUtil.memGetInt(srcPtr + off);
                if (pixel == prevIn) {
                    MemoryUtil.memPutInt(dstPtr + off, prevOut);
                    continue;
                }
                int alpha = pixel >>> 24;
                int out;
                if (alpha == 0) {
                    out = pixel;
                } else {
                    int rgb = pixel & 0x00FFFFFF;
                    int mapped = rgb | 0xFF000000;
                    for (int j = 0; j < mapLen; j++) {
                        if (k[j] == rgb) {
                            mapped = v[j];
                            break;
                        }
                    }
                    out = blendAlpha(alpha, mapped);
                }
                prevIn = pixel;
                prevOut = out;
                MemoryUtil.memPutInt(dstPtr + off, out);
            }
        }
    }

    private static final class MapPaletteMapper implements FastPaletteMapper {
        private final Int2IntMap map;

        private MapPaletteMapper(Int2IntMap map) {
            this.map = map;
        }

        @Override
        public int applyAsInt(int pixel) {
            int alpha = pixel >>> 24;
            if (alpha == 0) {
                return pixel;
            }
            int rgb = pixel & 0x00FFFFFF;
            return blendAlpha(alpha, this.map.getOrDefault(rgb, rgb | 0xFF000000));
        }

        @Override
        public void mapPixels(long srcPtr, long dstPtr, int pixelCount) {
            Int2IntMap m = this.map;
            int prevIn = 0;
            int prevOut = 0;
            for (int i = 0; i < pixelCount; i++) {
                long off = (long) i << 2;
                int pixel = MemoryUtil.memGetInt(srcPtr + off);
                if (pixel == prevIn) {
                    MemoryUtil.memPutInt(dstPtr + off, prevOut);
                    continue;
                }
                int alpha = pixel >>> 24;
                int out = alpha == 0
                        ? pixel
                        : blendAlpha(alpha, m.getOrDefault(pixel & 0x00FFFFFF, (pixel & 0x00FFFFFF) | 0xFF000000));
                prevIn = pixel;
                prevOut = out;
                MemoryUtil.memPutInt(dstPtr + off, out);
            }
        }
    }
}
