package com.ccr4ft3r.lightspeed.mixin.shape;

import com.ccr4ft3r.lightspeed.geometry.voxel.IndirectMergerCache;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.IndirectMerger;
import net.minecraft.world.phys.shapes.Shapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reuses immutable {@link IndirectMerger} instances on repeated coordinate lists during block
 * {@code VoxelShape} construction without allocating lookup keys.
 */
@Mixin(Shapes.class)
public abstract class ShapesIndirectMergerMixin {
    @Redirect(
            method = "createIndexMerger",
            at = @At(
                    value = "NEW",
                    target = "(Lit/unimi/dsi/fastutil/doubles/DoubleList;Lit/unimi/dsi/fastutil/doubles/DoubleList;ZZ)Lnet/minecraft/world/phys/shapes/IndirectMerger;"
            )
    )
    private static IndirectMerger lightspeed$reuseIndirectMerger(
            DoubleList first,
            DoubleList second,
            boolean firstOnly,
            boolean secondOnly
    ) {
        return IndirectMergerCache.getOrCreate(first, second, firstOnly, secondOnly);
    }
}
