package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.mojang.blaze3d.font.GlyphProvider;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.List;

/** Warms glyph providers in reverse native priority order without disk or hash overhead. */
public final class FontWarmupSnapshot {
    private FontWarmupSnapshot() {
    }

    public static void warmup(List<GlyphProvider> providers, GlyphProvider missing) {
        providers.add(0, missing);
        warmupExisting(providers);
    }

    public static void warmupExisting(List<GlyphProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return;
        }
        int providerCount = providers.size();
        GlyphProvider[] ordered = providers.toArray(new GlyphProvider[providerCount]);
        IntSet[] supported = new IntSet[providerCount];
        int maxGlyphs = 0;
        for (int i = 0; i < providerCount; i++) {
            IntSet glyphs = ordered[i].getSupportedGlyphs();
            supported[i] = glyphs;
            if (glyphs != null && glyphs.size() > maxGlyphs) {
                maxGlyphs = glyphs.size();
            }
        }
        IntOpenHashSet unique = new IntOpenHashSet(Math.max(16, maxGlyphs));
        for (int i = 0; i < providerCount; i++) {
            IntSet glyphs = supported[i];
            if (glyphs != null && !glyphs.isEmpty()) {
                unique.addAll(glyphs);
            }
        }
        IntIterator iterator = unique.iterator();
        while (iterator.hasNext()) {
            int character = iterator.nextInt();
            if (character == 32) {
                continue;
            }
            for (int i = providerCount - 1; i >= 0; i--) {
                if (ordered[i].getGlyph(character) != null) {
                    break;
                }
            }
        }
    }

    public static void persist() {
    }

    public static long hits() {
        return 0L;
    }

    public static long misses() {
        return 0L;
    }

    public static long failures() {
        return 0L;
    }
}
