package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.FontWarmupSnapshot;
import net.minecraft.client.gui.font.FontManager;
import com.mojang.blaze3d.font.GlyphProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(FontManager.class)
public abstract class FontManagerSnapshotMixin {
    @Inject(method = "finalizeProviderLoading", at = @At("HEAD"), cancellable = true)
    private void lightspeed$restoreFontWarmup(List<GlyphProvider> providers, GlyphProvider missing,
                                              CallbackInfo ci) {
        if (FontWarmupSnapshot.shouldSkip(providers)) {
            providers.add(0, missing);
            ci.cancel();
        }
    }

    @Inject(method = "finalizeProviderLoading", at = @At("RETURN"))
    private void lightspeed$recordFontWarmup(List<GlyphProvider> providers, GlyphProvider missing,
                                             CallbackInfo ci) {
        FontWarmupSnapshot.recordCompleted();
    }
}
