package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.FontWarmupSnapshot;
import net.minecraft.client.gui.font.FontManager;
import com.mojang.blaze3d.font.GlyphProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Replays cached character plans without skipping this reload's provider callbacks. */
@Mixin(FontManager.class)
public abstract class FontManagerSnapshotMixin {
    @Inject(method = "finalizeProviderLoading", at = @At("HEAD"), cancellable = true)
    private void lightspeed$restoreFontWarmup(List<GlyphProvider> providers, GlyphProvider missing,
                                              CallbackInfo ci) {
        FontWarmupSnapshot.warmup(providers, missing);
        ci.cancel();
    }
}
