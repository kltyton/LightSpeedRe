package com.ccr4ft3r.lightspeed.geometry.voxel;

import com.ccr4ft3r.lightspeed.mixin.shape.BitSetVoxelStorageAccess;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CubePointRange;
import net.minecraft.world.phys.shapes.DiscreteCubeMerger;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.IdenticalMerger;
import net.minecraft.world.phys.shapes.IndexMerger;
import net.minecraft.world.phys.shapes.IndirectMerger;

import java.util.Arrays;
import java.util.BitSet;

/** Expands occupied strips through the native coordinate merger using thread-local scratch buffers. */
public final class BitSetVoxelUnion {
    private static final ThreadLocal<FillScratch> SCRATCH = ThreadLocal.withInitial(FillScratch::new);

    private BitSetVoxelUnion() {}

    public static boolean fill(DiscreteVoxelShape first, DiscreteVoxelShape second,
            BitSetDiscreteVoxelShape result, IndexMerger x, IndexMerger y, IndexMerger z,
            BooleanOp operation, int[] bounds) {
        if (operation != BooleanOp.OR || result.getZSize() <= 1
                || !(first instanceof BitSetDiscreteVoxelShape)
                || !(second instanceof BitSetDiscreteVoxelShape)
                || !nativeMerger(x) || !nativeMerger(y) || !nativeMerger(z)
                || !initialBounds(bounds)) {
            return false;
        }
        BitSet firstBits = storage((BitSetDiscreteVoxelShape) first);
        BitSet secondBits = storage((BitSetDiscreteVoxelShape) second);
        BitSet resultBits = storage(result);
        if (firstBits.getClass() != BitSet.class || secondBits.getClass() != BitSet.class
                || resultBits.getClass() != BitSet.class) {
            return false;
        }

        int xLen = x.size() - 1;
        int yLen = y.size() - 1;
        int firstZSize = first.getZSize();
        int secondZSize = second.getZSize();
        int resultYSize = result.getYSize();
        int resultZSize = result.getZSize();

        FillScratch scratch = SCRATCH.get();
        scratch.prepareAxes(xLen, yLen, x, y);
        scratch.prepareZ(firstZSize, secondZSize, resultZSize, z);

        int[] xFirst = scratch.xFirst;
        int[] xSecond = scratch.xSecond;
        int[] yFirst = scratch.yFirst;
        int[] ySecond = scratch.ySecond;
        int[] firstZStarts = scratch.firstZStarts;
        int[] firstZEnds = scratch.firstZEnds;
        int[] secondZStarts = scratch.secondZStarts;
        int[] secondZEnds = scratch.secondZEnds;

        int firstXSize = first.getXSize();
        int firstYSize = first.getYSize();
        int secondXSize = second.getXSize();
        int secondYSize = second.getYSize();
        boolean hasFirstBits = !firstBits.isEmpty();
        boolean hasSecondBits = !secondBits.isEmpty();
        if (!hasFirstBits && !hasSecondBits) {
            return true;
        }

        if (resultZSize <= 63
                && (!hasFirstBits || firstZSize <= 64)
                && (!hasSecondBits || secondZSize <= 64)) {
            long[] firstWords = hasFirstBits ? firstBits.toLongArray() : null;
            long[] secondWords = hasSecondBits ? secondBits.toLongArray() : null;
            int firstRows = hasFirstBits ? firstXSize * firstYSize : 0;
            int secondRows = hasSecondBits ? secondXSize * secondYSize : 0;
            long[] firstMapped = (hasFirstBits && firstRows <= 4096) ? scratch.prepareFirstMapped(firstRows) : null;
            long[] secondMapped = (hasSecondBits && secondRows <= 4096) ? scratch.prepareSecondMapped(secondRows) : null;

            for (int outputX = 0; outputX < xLen; outputX++) {
                int fx = xFirst[outputX];
                int sx = xSecond[outputX];
                boolean checkFirstX = hasFirstBits && fx >= 0 && fx < firstXSize;
                boolean checkSecondX = hasSecondBits && sx >= 0 && sx < secondXSize;
                if (!checkFirstX && !checkSecondX) {
                    continue;
                }
                int xOutBase = outputX * resultYSize;
                int firstXBase = fx * firstYSize;
                int secondXBase = sx * secondYSize;
                for (int outputY = 0; outputY < yLen; outputY++) {
                    long outMask = 0L;
                    if (checkFirstX) {
                        int fy = yFirst[outputY];
                        if (fy >= 0 && fy < firstYSize) {
                            int fRow = firstXBase + fy;
                            if (firstMapped != null) {
                                long fm = firstMapped[fRow];
                                if (fm == -1L) {
                                    fm = mapSourceRowMask(firstWords, fRow, firstZSize, firstZStarts, firstZEnds);
                                    firstMapped[fRow] = fm;
                                }
                                outMask |= fm;
                            } else {
                                outMask |= mapSourceRowMask(firstWords, fRow, firstZSize, firstZStarts, firstZEnds);
                            }
                        }
                    }
                    if (checkSecondX) {
                        int sy = ySecond[outputY];
                        if (sy >= 0 && sy < secondYSize) {
                            int sRow = secondXBase + sy;
                            if (secondMapped != null) {
                                long sm = secondMapped[sRow];
                                if (sm == -1L) {
                                    sm = mapSourceRowMask(secondWords, sRow, secondZSize, secondZStarts, secondZEnds);
                                    secondMapped[sRow] = sm;
                                }
                                outMask |= sm;
                            } else {
                                outMask |= mapSourceRowMask(secondWords, sRow, secondZSize, secondZStarts, secondZEnds);
                            }
                        }
                    }
                    if (outMask != 0L) {
                        writeMappedMask(resultBits, (xOutBase + outputY) * resultZSize, outMask, outputX, outputY, bounds);
                    }
                }
            }
            return true;
        }

        for (int outputX = 0; outputX < xLen; outputX++) {
            int fx = xFirst[outputX];
            int sx = xSecond[outputX];
            boolean checkFirstX = hasFirstBits && fx >= 0 && fx < firstXSize;
            boolean checkSecondX = hasSecondBits && sx >= 0 && sx < secondXSize;
            if (!checkFirstX && !checkSecondX) {
                continue;
            }
            int xOutBase = outputX * resultYSize;
            int firstXBase = fx * firstYSize;
            int secondXBase = sx * secondYSize;
            for (int outputY = 0; outputY < yLen; outputY++) {
                int outputBase = (xOutBase + outputY) * resultZSize;
                if (checkFirstX) {
                    int fy = yFirst[outputY];
                    if (fy >= 0 && fy < firstYSize) {
                        copyRow(firstBits, firstXBase + fy, firstZSize, firstZStarts, firstZEnds,
                                resultBits, outputBase, outputX, outputY, bounds);
                    }
                }
                if (checkSecondX) {
                    int sy = ySecond[outputY];
                    if (sy >= 0 && sy < secondYSize) {
                        copyRow(secondBits, secondXBase + sy, secondZSize, secondZStarts, secondZEnds,
                                resultBits, outputBase, outputX, outputY, bounds);
                    }
                }
            }
        }
        return true;
    }

    /** Word-level greedy cuboid extraction matching BitSetDiscreteVoxelShape.forAllBoxes without BitSet cloning. */
    public static boolean forAllBoxes(DiscreteVoxelShape shape, DiscreteVoxelShape.IntLineConsumer consumer,
            boolean combine) {
        if (!(shape instanceof BitSetDiscreteVoxelShape bitShape)
                || bitShape.getClass() != BitSetDiscreteVoxelShape.class) {
            return false;
        }
        int xSize = bitShape.getXSize();
        int ySize = bitShape.getYSize();
        int zSize = bitShape.getZSize();
        if (xSize <= 0 || ySize <= 0 || zSize <= 0 || zSize > 64) {
            return false;
        }
        long rowCountLong = (long) xSize * ySize;
        if (rowCountLong > 65536L) {
            return false;
        }
        int rowCount = (int) rowCountLong;
        BitSet storage = storage(bitShape);
        if (storage.getClass() != BitSet.class) {
            return false;
        }
        if (storage.isEmpty()) {
            return true;
        }
        long[] words = storage.toLongArray();
        FillScratch scratch = SCRATCH.get();
        long[] rowMasks = scratch.acquireBoxMasks(rowCount);
        boolean borrowed = (rowMasks != null);
        if (!borrowed) {
            rowMasks = new long[rowCount];
        }
        try {
            long zMask = zSize == 64 ? -1L : ((1L << zSize) - 1L);
            for (int row = 0; row < rowCount; row++) {
                rowMasks[row] = extractRowBits(words, row, zSize, zMask);
            }
            for (int y = 0; y < ySize; y++) {
                for (int x = 0; x < xSize; x++) {
                    int rowIndex = x * ySize + y;
                    long rowMask = rowMasks[rowIndex];
                    while (rowMask != 0L) {
                        int startZ = Long.numberOfTrailingZeros(rowMask);
                        long shifted = rowMask >>> startZ;
                        int endZ = Math.min(zSize, startZ + Long.numberOfTrailingZeros(~shifted));
                        if (!combine) {
                            for (int z = startZ; z < endZ; z++) {
                                consumer.consume(x, y, z, x + 1, y + 1, z + 1);
                            }
                            rowMask &= (endZ >= 64 ? 0L : (-1L << endZ));
                            continue;
                        }
                        int len = endZ - startZ;
                        long runMask = (len >= 64 ? -1L : ((1L << len) - 1L)) << startZ;
                        rowMask &= ~runMask;
                        rowMasks[rowIndex] = rowMask;
                        int endX = x;
                        while (endX + 1 < xSize && (rowMasks[(endX + 1) * ySize + y] & runMask) == runMask) {
                            endX++;
                            rowMasks[endX * ySize + y] &= ~runMask;
                        }
                        int endY = y;
                        while (endY + 1 < ySize && isRectFull(rowMasks, x, endX, endY + 1, ySize, runMask)) {
                            endY++;
                            for (int cx = x; cx <= endX; cx++) {
                                rowMasks[cx * ySize + endY] &= ~runMask;
                            }
                        }
                        consumer.consume(x, y, startZ, endX + 1, endY + 1, endZ);
                    }
                }
            }
            return true;
        } finally {
            if (borrowed) {
                scratch.releaseBoxMasks();
            }
        }
    }

    private static boolean isRectFull(long[] rowMasks, int startX, int endX, int y, int ySize, long runMask) {
        for (int cx = startX; cx <= endX; cx++) {
            if ((rowMasks[cx * ySize + y] & runMask) != runMask) {
                return false;
            }
        }
        return true;
    }

    private static long extractRowBits(long[] words, int rowIndex, int zSize, long zMask) {
        int base = rowIndex * zSize;
        int wordIdx = base >>> 6;
        if (wordIdx >= words.length) {
            return 0L;
        }
        int bitOff = base & 63;
        long raw = words[wordIdx] >>> bitOff;
        if (bitOff + zSize > 64 && wordIdx + 1 < words.length) {
            raw |= words[wordIdx + 1] << (64 - bitOff);
        }
        return raw & zMask;
    }

    private static BitSet storage(BitSetDiscreteVoxelShape shape) {
        return ((BitSetVoxelStorageAccess) (Object) shape).lightspeed$storage();
    }

    private static boolean nativeMerger(IndexMerger merger) {
        Class<?> type = merger.getClass();
        if (type == IndirectMerger.class || type == DiscreteCubeMerger.class) {
            return true;
        }
        if (type == IdenticalMerger.class) {
            Class<?> coordinates = merger.getList().getClass();
            return coordinates == DoubleArrayList.class || coordinates == CubePointRange.class;
        }
        return false;
    }

    private static boolean initialBounds(int[] bounds) {
        return bounds.length == 6
                && bounds[0] == Integer.MAX_VALUE && bounds[1] == Integer.MAX_VALUE
                && bounds[2] == Integer.MAX_VALUE && bounds[3] == Integer.MIN_VALUE
                && bounds[4] == Integer.MIN_VALUE && bounds[5] == Integer.MIN_VALUE;
    }

    private static long mapSourceRowMask(long[] words, int rowIndex, int zSize, int[] starts, int[] ends) {
        long zMask = zSize == 64 ? -1L : ((1L << zSize) - 1L);
        long mask = extractRowBits(words, rowIndex, zSize, zMask);
        if (mask == 0L) {
            return 0L;
        }
        long mapped = 0L;
        while (mask != 0L) {
            int bit = Long.numberOfTrailingZeros(mask);
            long t = mask >>> bit;
            int end = Math.min(zSize, bit + Long.numberOfTrailingZeros(~t));
            int from = starts[bit];
            int to = ends[end - 1];
            if (from < to) {
                int len = to - from;
                long runMask = len >= 64 ? -1L : ((1L << len) - 1L);
                mapped |= (runMask << from);
            }
            mask &= (end >= 64 ? 0L : (-1L << end));
        }
        return mapped;
    }

    private static void writeMappedMask(
            BitSet target,
            int outputBase,
            long outMask,
            int outputX,
            int outputY,
            int[] bounds
    ) {
        bounds[0] = Math.min(bounds[0], outputX);
        bounds[1] = Math.min(bounds[1], outputY);
        bounds[3] = Math.max(bounds[3], outputX);
        bounds[4] = Math.max(bounds[4], outputY);

        int lo = Long.numberOfTrailingZeros(outMask);
        bounds[2] = Math.min(bounds[2], lo);
        long shifted = outMask >>> lo;
        if ((shifted & (shifted + 1L)) == 0L) {
            int hi = lo + Long.numberOfTrailingZeros(~shifted);
            target.set(outputBase + lo, outputBase + hi);
            bounds[5] = Math.max(bounds[5], hi - 1);
            return;
        }

        long m = outMask;
        int maxZ = bounds[5];
        while (m != 0L) {
            int z0 = Long.numberOfTrailingZeros(m);
            long t = m >>> z0;
            int z1 = z0 + Long.numberOfTrailingZeros(~t);
            target.set(outputBase + z0, outputBase + z1);
            if (z1 - 1 > maxZ) {
                maxZ = z1 - 1;
            }
            m &= (z1 >= 64 ? 0L : (-1L << z1));
        }
        bounds[5] = maxZ;
    }

    private static void copyRow(BitSet source, int rowIndex, int zSize,
            int[] starts, int[] ends, BitSet target, int outputBase, int outputX, int outputY, int[] bounds) {
        int base = rowIndex * zSize;
        int limit = base + zSize;
        for (int bit = source.nextSetBit(base); bit >= 0 && bit < limit;) {
            int end = Math.min(source.nextClearBit(bit), limit);
            int from = starts[bit - base];
            int to = ends[end - base - 1];
            if (from < to) {
                target.set(outputBase + from, outputBase + to);
                bounds[0] = Math.min(bounds[0], outputX);
                bounds[1] = Math.min(bounds[1], outputY);
                bounds[2] = Math.min(bounds[2], from);
                bounds[3] = Math.max(bounds[3], outputX);
                bounds[4] = Math.max(bounds[4], outputY);
                bounds[5] = Math.max(bounds[5], to - 1);
            }
            bit = source.nextSetBit(end);
        }
    }

    private static final class FillScratch {
        private int[] xFirst = new int[16];
        private int[] xSecond = new int[16];
        private int[] yFirst = new int[16];
        private int[] ySecond = new int[16];
        private int[] firstZStarts = new int[16];
        private int[] firstZEnds = new int[16];
        private int[] secondZStarts = new int[16];
        private int[] secondZEnds = new int[16];
        private int activeFirstZSize;
        private int activeSecondZSize;
        private long[] firstMapped = new long[256];
        private long[] secondMapped = new long[256];
        private long[] boxMasks = new long[1024];
        private boolean boxMasksInUse;

        private long[] acquireBoxMasks(int rows) {
            if (boxMasksInUse) {
                return null;
            }
            boxMasksInUse = true;
            if (rows > boxMasks.length) {
                boxMasks = new long[rows];
            }
            return boxMasks;
        }

        private void releaseBoxMasks() {
            boxMasksInUse = false;
        }

        private long[] prepareFirstMapped(int rows) {
            if (rows > firstMapped.length) {
                firstMapped = new long[rows];
            }
            Arrays.fill(firstMapped, 0, rows, -1L);
            return firstMapped;
        }

        private long[] prepareSecondMapped(int rows) {
            if (rows > secondMapped.length) {
                secondMapped = new long[rows];
            }
            Arrays.fill(secondMapped, 0, rows, -1L);
            return secondMapped;
        }

        private final IndexMerger.IndexConsumer xConsumer = (a, b, out) -> {
            xFirst[out] = a;
            xSecond[out] = b;
            return true;
        };
        private final IndexMerger.IndexConsumer yConsumer = (a, b, out) -> {
            yFirst[out] = a;
            ySecond[out] = b;
            return true;
        };
        private final IndexMerger.IndexConsumer zConsumer = (a, b, out) -> {
            if (a >= 0 && a < activeFirstZSize) {
                if (firstZStarts[a] < 0) firstZStarts[a] = out;
                firstZEnds[a] = out + 1;
            }
            if (b >= 0 && b < activeSecondZSize) {
                if (secondZStarts[b] < 0) secondZStarts[b] = out;
                secondZEnds[b] = out + 1;
            }
            return true;
        };

        private void prepareAxes(int xLen, int yLen, IndexMerger x, IndexMerger y) {
            if (xLen > xFirst.length) {
                xFirst = new int[xLen];
                xSecond = new int[xLen];
            }
            if (yLen > yFirst.length) {
                yFirst = new int[yLen];
                ySecond = new int[yLen];
            }
            x.forMergedIndexes(xConsumer);
            y.forMergedIndexes(yConsumer);
        }

        private void prepareZ(int firstZSize, int secondZSize, int resultZSize, IndexMerger z) {
            if (firstZSize > firstZStarts.length) {
                firstZStarts = new int[firstZSize];
                firstZEnds = new int[firstZSize];
            }
            if (secondZSize > secondZStarts.length) {
                secondZStarts = new int[secondZSize];
                secondZEnds = new int[secondZSize];
            }
            Arrays.fill(firstZStarts, 0, firstZSize, -1);
            Arrays.fill(firstZEnds, 0, firstZSize, 0);
            Arrays.fill(secondZStarts, 0, secondZSize, -1);
            Arrays.fill(secondZEnds, 0, secondZSize, 0);
            activeFirstZSize = firstZSize;
            activeSecondZSize = secondZSize;
            z.forMergedIndexes(zConsumer);
            finishRanges(firstZStarts, firstZEnds, firstZSize, resultZSize);
            finishRanges(secondZStarts, secondZEnds, secondZSize, resultZSize);
        }

        private static void finishRanges(int[] starts, int[] ends, int len, int outputSize) {
            // Native mappings are monotone; clipped source cells share the next/previous boundary.
            int next = outputSize;
            for (int i = len - 1; i >= 0; i--) {
                if (starts[i] >= 0) next = starts[i];
                starts[i] = next;
            }
            int previous = 0;
            for (int i = 0; i < len; i++) {
                if (ends[i] != 0) previous = ends[i];
                ends[i] = previous;
            }
        }
    }
}
