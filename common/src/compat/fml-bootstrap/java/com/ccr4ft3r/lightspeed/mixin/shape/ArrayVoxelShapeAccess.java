package com.ccr4ft3r.lightspeed.mixin.shape;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes package-private {@link ArrayVoxelShape} list constructor for single-pass optimize. */
@Mixin(ArrayVoxelShape.class)
public interface ArrayVoxelShapeAccess {
    @Invoker("<init>")
    static ArrayVoxelShape lightspeed$create(
            DiscreteVoxelShape shape,
            DoubleList xs,
            DoubleList ys,
            DoubleList zs
    ) {
        throw new AssertionError();
    }
}
