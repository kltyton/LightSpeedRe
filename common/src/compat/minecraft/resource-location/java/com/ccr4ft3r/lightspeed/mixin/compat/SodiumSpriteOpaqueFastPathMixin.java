package com.ccr4ft3r.lightspeed.mixin.compat;

import com.ccr4ft3r.lightspeed.mixin.client.NativeImageAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fast opaque-texture early exit for Sodium/Embeddium's transparent-pixel color fill pass.
 * When a sprite has zero alpha == 0 pixels, the fill pass writes nothing; a single native-memory
 * alpha scan preserves bit-identical output while avoiding two full sRGB passes on opaque sprites.
 */
@Mixin(value = SpriteContents.class, priority = 1500)
public abstract class SodiumSpriteOpaqueFastPathMixin {
    @Unique
    private static final boolean LIGHTSPEED_FAST_OPAQUE =
            Boolean.parseBoolean(System.getProperty("lightspeed.fastOpaqueSprites", "true"));

    @Inject(method = "sodium$fillInTransparentPixelColors", at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private static void lightspeed$skipOpaqueFill(NativeImage image, CallbackInfo ci) {
        if (!LIGHTSPEED_FAST_OPAQUE || image == null || image.format() != NativeImage.Format.RGBA) {
            return;
        }
        long ptr = ((NativeImageAccessor) (Object) image).lightspeed$pixels();
        if (ptr == 0L) {
            return;
        }
        int count = image.getWidth() * image.getHeight();
        for (int i = 0; i < count; i++) {
            if ((MemoryUtil.memGetInt(ptr + ((long) i << 2)) & 0xFF000000) == 0) {
                return;
            }
        }
        ci.cancel();
    }
}
