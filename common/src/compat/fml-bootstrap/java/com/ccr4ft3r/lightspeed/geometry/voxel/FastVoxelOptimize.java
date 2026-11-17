package com.ccr4ft3r.lightspeed.geometry.voxel;

import com.ccr4ft3r.lightspeed.mixin.shape.ArrayVoxelShapeAccess;
import com.ccr4ft3r.lightspeed.mixin.shape.BitSetVoxelStorageAccess;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Replaces vanilla {@link VoxelShape#optimize()} {@code O(K^2)} incremental {@code joinUnoptimized}
 * chains with a single-pass snapped grid construction for multi-box shapes.
 */
public final class FastVoxelOptimize {
    private static final double EPSILON = 1.0E-7;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private FastVoxelOptimize() {
    }

    public static VoxelShape optimize(VoxelShape shape) {
        Scratch s = SCRATCH.get();
        s.count = 0;
        shape.forAllBoxes(s);
        int count = s.count;
        if (count == 0) {
            return Shapes.empty();
        }
        double[] b = s.boxes;
        if (count == 1) {
            return Shapes.box(b[0], b[1], b[2], b[3], b[4], b[5]);
        }

        double[] xs = s.ensureXs(count * 2);
        double[] ys = s.ensureYs(count * 2);
        double[] zs = s.ensureZs(count * 2);
        for (int i = 0, j = 0; i < count; i++, j += 6) {
            int p = i << 1;
            xs[p] = b[j];
            xs[p + 1] = b[j + 3];
            ys[p] = b[j + 1];
            ys[p + 1] = b[j + 4];
            zs[p] = b[j + 2];
            zs[p + 1] = b[j + 5];
        }
        int nx = uniqueSortedCoords(xs, count * 2);
        int ny = uniqueSortedCoords(ys, count * 2);
        int nz = uniqueSortedCoords(zs, count * 2);
        if (nx < 2 || ny < 2 || nz < 2) {
            return Shapes.empty();
        }
        int xSize = nx - 1;
        int ySize = ny - 1;
        int zSize = nz - 1;
        if (xSize == 1 && ySize == 1 && zSize == 1) {
            return Shapes.box(xs[0], ys[0], zs[0], xs[1], ys[1], zs[1]);
        }

        BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(xSize, ySize, zSize);
        BitSetVoxelStorageAccess access = (BitSetVoxelStorageAccess) (Object) discrete;
        BitSet storage = access.lightspeed$storage();
        int xMin = Integer.MAX_VALUE;
        int yMin = Integer.MAX_VALUE;
        int zMin = Integer.MAX_VALUE;
        int xMax = 0;
        int yMax = 0;
        int zMax = 0;
        boolean anyFilled = false;

        for (int i = 0, j = 0; i < count; i++, j += 6) {
            int ix0 = findCoord(xs, nx, b[j]);
            int ix1 = findCoord(xs, nx, b[j + 3]);
            int iy0 = findCoord(ys, ny, b[j + 1]);
            int iy1 = findCoord(ys, ny, b[j + 4]);
            int iz0 = findCoord(zs, nz, b[j + 2]);
            int iz1 = findCoord(zs, nz, b[j + 5]);
            if (ix0 >= ix1 || iy0 >= iy1 || iz0 >= iz1) {
                continue;
            }
            anyFilled = true;
            if (ix0 < xMin) xMin = ix0;
            if (iy0 < yMin) yMin = iy0;
            if (iz0 < zMin) zMin = iz0;
            if (ix1 > xMax) xMax = ix1;
            if (iy1 > yMax) yMax = iy1;
            if (iz1 > zMax) zMax = iz1;
            for (int x = ix0; x < ix1; x++) {
                int xBase = x * ySize;
                for (int y = iy0; y < iy1; y++) {
                    int rowStart = (xBase + y) * zSize;
                    storage.set(rowStart + iz0, rowStart + iz1);
                }
            }
        }
        if (!anyFilled) {
            return Shapes.empty();
        }
        access.lightspeed$setXMin(xMin);
        access.lightspeed$setYMin(yMin);
        access.lightspeed$setZMin(zMin);
        access.lightspeed$setXMax(xMax);
        access.lightspeed$setYMax(yMax);
        access.lightspeed$setZMax(zMax);
        return ArrayVoxelShapeAccess.lightspeed$create(
                discrete,
                DoubleArrayList.wrap(Arrays.copyOf(xs, nx)),
                DoubleArrayList.wrap(Arrays.copyOf(ys, ny)),
                DoubleArrayList.wrap(Arrays.copyOf(zs, nz)));
    }

    private static int uniqueSortedCoords(double[] coords, int len) {
        Arrays.sort(coords, 0, len);
        int out = 0;
        double prev = Double.NaN;
        for (int i = 0; i < len; i++) {
            double c = coords[i];
            if (out == 0 || Double.doubleToRawLongBits(c) != Double.doubleToRawLongBits(prev)) {
                coords[out++] = c;
                prev = c;
            }
        }
        return out;
    }

    private static int findCoord(double[] coords, int len, double value) {
        int idx = Arrays.binarySearch(coords, 0, len, value);
        if (idx >= 0) {
            return idx;
        }
        int ins = -idx - 1;
        return ins >= len ? len - 1 : ins;
    }

    private static final class Scratch implements Shapes.DoubleLineConsumer {
        private double[] boxes = new double[64 * 6];
        private double[] xs = new double[128];
        private double[] ys = new double[128];
        private double[] zs = new double[128];
        private int count;

        @Override
        public void consume(double x0, double y0, double z0, double x1, double y1, double z1) {
            if (x1 - x0 < EPSILON || y1 - y0 < EPSILON || z1 - z0 < EPSILON) {
                return;
            }
            int idx = count * 6;
            if (idx + 6 > boxes.length) {
                boxes = Arrays.copyOf(boxes, boxes.length << 1);
            }
            boxes[idx] = x0;
            boxes[idx + 1] = y0;
            boxes[idx + 2] = z0;
            boxes[idx + 3] = x1;
            boxes[idx + 4] = y1;
            boxes[idx + 5] = z1;
            count++;
        }

        private double[] ensureXs(int n) {
            if (xs.length < n) xs = new double[n];
            return xs;
        }

        private double[] ensureYs(int n) {
            if (ys.length < n) ys = new double[n];
            return ys;
        }

        private double[] ensureZs(int n) {
            if (zs.length < n) zs = new double[n];
            return zs;
        }
    }
}
