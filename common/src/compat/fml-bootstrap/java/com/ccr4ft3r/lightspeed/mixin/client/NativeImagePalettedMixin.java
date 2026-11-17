package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.texture.FastPaletteMapper;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.function.IntUnaryOperator;

/**
 * Eliminates {@code DirectIntBufferU} wrapper allocation and {@code ScopedMemoryAccess} bounds
 * overhead on {@code NativeImage.mappedCopy} and {@code applyToAllPixels}.
 */
@Mixin(NativeImage.class)
public abstract class NativeImagePalettedMixin {
    @Shadow @Final private NativeImage.Format format;
    @Shadow @Final private int width;
    @Shadow @Final private int height;
    @Shadow private long pixels;

    @Shadow
    private void checkAllocated() {
        throw new AssertionError();
    }

    @Unique
    private long lightspeed$pixelsAddress() {
        return this.pixels;
    }

    @Inject(method = "mappedCopy", at = @At("HEAD"), cancellable = true)
    private void lightspeed$fastMappedCopy(IntUnaryOperator operator, CallbackInfoReturnable<NativeImage> cir) {
        if (this.format != NativeImage.Format.RGBA) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT, "function application only works on RGBA images; have %s", this.format));
        }
        this.checkAllocated();
        NativeImage copy = new NativeImage(this.width, this.height, false);
        int count = this.width * this.height;
        long srcPtr = this.pixels;
        long dstPtr = ((NativeImagePalettedMixin) (Object) copy).lightspeed$pixelsAddress();
        if (operator instanceof FastPaletteMapper fast) {
            fast.mapPixels(srcPtr, dstPtr, count);
        } else {
            for (int i = 0; i < count; i++) {
                long off = (long) i << 2;
                MemoryUtil.memPutInt(dstPtr + off, operator.applyAsInt(MemoryUtil.memGetInt(srcPtr + off)));
            }
        }
        cir.setReturnValue(copy);
    }

    @Inject(method = "applyToAllPixels", at = @At("HEAD"), cancellable = true)
    private void lightspeed$fastApplyToAllPixels(IntUnaryOperator operator, CallbackInfo ci) {
        if (this.format != NativeImage.Format.RGBA) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT, "function application only works on RGBA images; have %s", this.format));
        }
        this.checkAllocated();
        int count = this.width * this.height;
        long ptr = this.pixels;
        if (operator instanceof FastPaletteMapper fast) {
            fast.mapPixels(ptr, ptr, count);
        } else {
            for (int i = 0; i < count; i++) {
                long off = (long) i << 2;
                MemoryUtil.memPutInt(ptr + off, operator.applyAsInt(MemoryUtil.memGetInt(ptr + off)));
            }
        }
        ci.cancel();
    }
}
