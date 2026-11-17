package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.texture.PaletteMappingSupport;
import net.minecraft.client.renderer.texture.atlas.sources.PalettedPermutations;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.IntUnaryOperator;

/**
 * Replaces vanilla {@code Int2IntOpenHashMap} palette remappers with compact primitive linear lookup
 * for small palettes and off-heap direct pixel batch loops paired with single-pixel run caching.
 */
@Mixin(PalettedPermutations.class)
public abstract class PalettedPermutationsFastMixin {
    @Shadow @Final static Logger LOGGER;

    @Inject(method = "createPaletteMapping", at = @At("HEAD"), cancellable = true)
    private static void lightspeed$createFastPaletteMapping(
            int[] sourcePalette,
            int[] targetPalette,
            CallbackInfoReturnable<IntUnaryOperator> cir
    ) {
        cir.setReturnValue(PaletteMappingSupport.createPaletteMapping(sourcePalette, targetPalette, LOGGER));
    }
}
