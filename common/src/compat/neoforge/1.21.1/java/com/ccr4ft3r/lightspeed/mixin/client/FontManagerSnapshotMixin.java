package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.FontWarmupSnapshot;
import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.ArrayList;

/** Replays the native provider warmup sequence using the shared font plan. */
@Mixin(FontManager.class)
public abstract class FontManagerSnapshotMixin {
    @Inject(method = "finalizeProviderLoading", at = @At("HEAD"), cancellable = true)
    private void lightspeed$restoreFontWarmup(
            List<GlyphProvider.Conditional> providers,
            GlyphProvider.Conditional missing,
            CallbackInfo ci) {
        providers.add(0, missing);
        List<GlyphProvider> currentProviders = new ArrayList<>(providers.size());
        for (GlyphProvider.Conditional conditional : providers) {
            currentProviders.add(conditional.provider());
        }
        FontWarmupSnapshot.warmupExisting(currentProviders);
        ci.cancel();
    }
}
