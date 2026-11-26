package com.ccr4ft3r.lightspeed.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Accelerates mipmap generation with row-major direct off-heap memory access and uniform 2x2 block
 * short-circuiting while delegating mixed blocks to the live alphaBlend method (preserving shader mods).
 */
@Mixin(value = MipmapGenerator.class, priority = 1500)
public abstract class MipmapFastPathMixin {
    @Unique
    private static final boolean LIGHTSPEED_FAST_MIPMAP =
            Boolean.parseBoolean(System.getProperty("lightspeed.fastMipmap", "true"));

    @Shadow
    private static int alphaBlend(int c0, int c1, int c2, int c3, boolean hasAlpha) {
        throw new AssertionError();
    }

    @Inject(method = "generateMipLevels", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lightspeed$fastGenerateMipLevels(
            NativeImage[] images,
            int mipLevel,
            CallbackInfoReturnable<NativeImage[]> cir) {
        if (!LIGHTSPEED_FAST_MIPMAP || images == null || images.length == 0 || mipLevel + 1 <= images.length) {
            return;
        }
        NativeImage base = images[0];
        if (base == null || base.format() != NativeImage.Format.RGBA
                || (base.getWidth() >> mipLevel) <= 0 || (base.getHeight() >> mipLevel) <= 0) {
            return;
        }
        long basePtr = ((NativeImageAccessor) (Object) base).lightspeed$pixels();
        if (basePtr == 0L) {
            return;
        }

        NativeImage[] result = new NativeImage[mipLevel + 1];
        result[0] = base;

        int basePixels = base.getWidth() * base.getHeight();
        boolean hasAlpha = false;
        for (int i = 0; i < basePixels; i++) {
            if ((MemoryUtil.memGetInt(basePtr + ((long) i << 2)) & 0xFF000000) == 0) {
                hasAlpha = true;
                break;
            }
        }

        for (int level = 1; level <= mipLevel; level++) {
            if (level < images.length) {
                result[level] = images[level];
                continue;
            }
            NativeImage src = result[level - 1];
            int srcW = src.getWidth();
            int dstW = srcW >> 1;
            int dstH = src.getHeight() >> 1;
            NativeImage dst = new NativeImage(dstW, dstH, false);
            long srcPtr = ((NativeImageAccessor) (Object) src).lightspeed$pixels();
            long dstPtr = ((NativeImageAccessor) (Object) dst).lightspeed$pixels();
            if (srcPtr == 0L || dstPtr == 0L) {
                dst.close();
                for (int prev = images.length; prev < level; prev++) {
                    if (result[prev] != null) {
                        result[prev].close();
                    }
                }
                return;
            }

            long srcRowBytes = (long) srcW << 2;
            long dstRowBytes = (long) dstW << 2;
            for (int y = 0; y < dstH; y++) {
                long row0 = srcPtr + ((long) (y << 1)) * srcRowBytes;
                long row1 = row0 + srcRowBytes;
                long outRow = dstPtr + (long) y * dstRowBytes;
                for (int x = 0; x < dstW; x++) {
                    long sx = (long) x << 3;
                    int c0 = MemoryUtil.memGetInt(row0 + sx);
                    int c1 = MemoryUtil.memGetInt(row0 + sx + 4L);
                    int c2 = MemoryUtil.memGetInt(row1 + sx);
                    int c3 = MemoryUtil.memGetInt(row1 + sx + 4L);
                    int out;
                    if (c0 == c1 && c0 == c2 && c0 == c3) {
                        if (c0 == 0) {
                            out = 0;
                        } else if (!hasAlpha || (c0 >>> 24) >= 96) {
                            out = c0;
                        } else {
                            out = alphaBlend(c0, c1, c2, c3, true);
                        }
                    } else {
                        out = alphaBlend(c0, c1, c2, c3, hasAlpha);
                    }
                    MemoryUtil.memPutInt(outRow + ((long) x << 2), out);
                }
            }
            result[level] = dst;
        }
        cir.setReturnValue(result);
    }
}
