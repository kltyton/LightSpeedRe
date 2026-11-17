package com.ccr4ft3r.lightspeed.geometry.voxel;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.IndirectMerger;

/**
 * Reuses immutable {@link IndirectMerger} instances on repeated coordinate lists during block
 * {@code VoxelShape} construction without allocating lookup keys.
 */
public final class IndirectMergerCache {
    private static final int MAX_CACHED_COORDS = 32;
    private static final int BUCKET_COUNT = 256;
    private static final int BUCKET_MASK = BUCKET_COUNT - 1;
    private static final ThreadLocal<MergerSlot[]> THREAD_CACHE =
            ThreadLocal.withInitial(() -> new MergerSlot[BUCKET_COUNT * 2]);

    private IndirectMergerCache() {
    }

    public static IndirectMerger getOrCreate(
            DoubleList first,
            DoubleList second,
            boolean firstOnly,
            boolean secondOnly
    ) {
        int n1 = first.size();
        int n2 = second.size();
        if (n1 > MAX_CACHED_COORDS || n2 > MAX_CACHED_COORDS) {
            return new IndirectMerger(first, second, firstOnly, secondOnly);
        }
        int hash = hashArgs(first, n1, second, n2, firstOnly, secondOnly);
        MergerSlot[] table = THREAD_CACHE.get();
        int base = (hash & BUCKET_MASK) << 1;

        MergerSlot primary = table[base];
        if (primary != null && primary.matches(hash, first, n1, second, n2, firstOnly, secondOnly)) {
            return primary.merger;
        }
        MergerSlot secondary = table[base + 1];
        if (secondary != null && secondary.matches(hash, first, n1, second, n2, firstOnly, secondOnly)) {
            table[base + 1] = primary;
            table[base] = secondary;
            return secondary.merger;
        }

        IndirectMerger created = new IndirectMerger(first, second, firstOnly, secondOnly);
        MergerSlot slot = new MergerSlot(hash, snapshot(first, n1), snapshot(second, n2),
                firstOnly, secondOnly, created);
        table[base + 1] = primary;
        table[base] = slot;
        return created;
    }

    private static int hashArgs(
            DoubleList first,
            int n1,
            DoubleList second,
            int n2,
            boolean firstOnly,
            boolean secondOnly
    ) {
        int h = (n1 * 31 + n2) * 31 + (firstOnly ? 1231 : 1237);
        h = h * 31 + (secondOnly ? 1231 : 1237);
        for (int i = 0; i < n1; i++) {
            long bits = Double.doubleToRawLongBits(first.getDouble(i));
            h = h * 31 + (int) (bits ^ (bits >>> 32));
        }
        for (int i = 0; i < n2; i++) {
            long bits = Double.doubleToRawLongBits(second.getDouble(i));
            h = h * 31 + (int) (bits ^ (bits >>> 32));
        }
        h ^= (h >>> 16);
        return h;
    }

    private static double[] snapshot(DoubleList list, int len) {
        double[] copy = new double[len];
        for (int i = 0; i < len; i++) {
            copy[i] = list.getDouble(i);
        }
        return copy;
    }

    private static final class MergerSlot {
        private final int hash;
        private final double[] firstCoords;
        private final double[] secondCoords;
        private final boolean firstOnly;
        private final boolean secondOnly;
        private final IndirectMerger merger;

        private MergerSlot(
                int hash,
                double[] firstCoords,
                double[] secondCoords,
                boolean firstOnly,
                boolean secondOnly,
                IndirectMerger merger
        ) {
            this.hash = hash;
            this.firstCoords = firstCoords;
            this.secondCoords = secondCoords;
            this.firstOnly = firstOnly;
            this.secondOnly = secondOnly;
            this.merger = merger;
        }

        private boolean matches(
                int hash,
                DoubleList first,
                int n1,
                DoubleList second,
                int n2,
                boolean firstOnly,
                boolean secondOnly
        ) {
            if (this.hash != hash
                    || this.firstOnly != firstOnly
                    || this.secondOnly != secondOnly
                    || this.firstCoords.length != n1
                    || this.secondCoords.length != n2) {
                return false;
            }
            for (int i = 0; i < n1; i++) {
                if (Double.doubleToRawLongBits(first.getDouble(i))
                        != Double.doubleToRawLongBits(this.firstCoords[i])) {
                    return false;
                }
            }
            for (int i = 0; i < n2; i++) {
                if (Double.doubleToRawLongBits(second.getDouble(i))
                        != Double.doubleToRawLongBits(this.secondCoords[i])) {
                    return false;
                }
            }
            return true;
        }
    }
}
