package com.ccr4ft3r.lightspeed.mixin.shape;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.geometry.voxel.FastVoxelOptimize;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VoxelShape.class)
public abstract class VoxelShapeOptimizeMixin {
    @Inject(method = "optimize", at = @At("HEAD"), cancellable = true)
    private void lightspeed$optimizeSinglePass(CallbackInfoReturnable<VoxelShape> cir) {
        if (GlobalCache.isEnabled) {
            cir.setReturnValue(FastVoxelOptimize.optimize((VoxelShape) (Object) this));
        }
    }
}
