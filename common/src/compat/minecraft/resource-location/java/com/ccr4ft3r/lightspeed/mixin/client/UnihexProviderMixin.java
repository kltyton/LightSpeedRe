package com.ccr4ft3r.lightspeed.mixin.client;

import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.font.providers.UnihexProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(UnihexProvider.class)
public abstract class UnihexProviderMixin {
    @Unique
    private volatile IntSet lightspeed$supportedGlyphs;

    @Inject(method = "getSupportedGlyphs", at = @At("HEAD"), cancellable = true)
    private void lightspeed$returnCachedGlyphs(CallbackInfoReturnable<IntSet> cir) {
        IntSet cached = this.lightspeed$supportedGlyphs;
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getSupportedGlyphs", at = @At("RETURN"), cancellable = true)
    private void lightspeed$cacheSupportedGlyphs(CallbackInfoReturnable<IntSet> cir) {
        IntSet returned = cir.getReturnValue();
        if (returned != null) {
            this.lightspeed$supportedGlyphs = returned;
        }
    }
}
