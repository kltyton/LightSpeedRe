package com.ccr4ft3r.lightspeed.mixin.shape;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.geometry.voxel.BitSetVoxelUnion;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.IndexMerger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps native construction and bounds publication around occupied strip combinations. */
@Mixin(value = BitSetDiscreteVoxelShape.class, priority = 1100)
public abstract class BitSetVoxelUnionMixin {
    @Inject(method = "forAllBoxes", at = @At("HEAD"), cancellable = true)
    private static void lightspeed$forAllBoxesWords(DiscreteVoxelShape shape,
            DiscreteVoxelShape.IntLineConsumer consumer, boolean combine, CallbackInfo ci) {
        if (GlobalCache.isEnabled && BitSetVoxelUnion.forAllBoxes(shape, consumer, combine)) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "join", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/shapes/IndexMerger;forMergedIndexes(Lnet/minecraft/world/phys/shapes/IndexMerger$IndexConsumer;)Z"), require = 1)
    private static boolean lightspeed$unionStrips(IndexMerger x, IndexMerger.IndexConsumer consumer,
            Operation<Boolean> original,
            @Local(argsOnly = true, ordinal = 0) DiscreteVoxelShape first,
            @Local(argsOnly = true, ordinal = 1) DiscreteVoxelShape second,
            @Local(argsOnly = true, ordinal = 1) IndexMerger y,
            @Local(argsOnly = true, ordinal = 2) IndexMerger z,
            @Local(argsOnly = true) BooleanOp operation,
            @Local BitSetDiscreteVoxelShape result,
            @Local(ordinal = 0) int[] bounds) {
        return GlobalCache.isEnabled && BitSetVoxelUnion.fill(first, second, result, x, y, z, operation, bounds)
                || original.call(x, consumer);
    }
}
