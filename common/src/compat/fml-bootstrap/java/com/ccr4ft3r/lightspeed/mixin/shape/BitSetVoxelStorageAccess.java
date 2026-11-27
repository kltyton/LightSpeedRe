package com.ccr4ft3r.lightspeed.mixin.shape;

import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.BitSet;

/** Reads and installs the native occupancy bits for startup geometry preparation. */
@Mixin(BitSetDiscreteVoxelShape.class)
public interface BitSetVoxelStorageAccess {
    @Accessor("storage")
    BitSet lightspeed$storage();

    @Mutable
    @Accessor("storage")
    void lightspeed$setStorage(BitSet storage);

    @Accessor("xMin") void lightspeed$setXMin(int value);
    @Accessor("yMin") void lightspeed$setYMin(int value);
    @Accessor("zMin") void lightspeed$setZMin(int value);
    @Accessor("xMax") void lightspeed$setXMax(int value);
    @Accessor("yMax") void lightspeed$setYMax(int value);
    @Accessor("zMax") void lightspeed$setZMax(int value);
}
