package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.NativeImageSnapshot;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;

@Mixin(NativeImage.class)
public abstract class NativeImageReadMixin {
    @Inject(method = "read(Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At("HEAD"), cancellable = true)
    private static void lightspeed$restoreSnapshot(InputStream input,
                                                    CallbackInfoReturnable<NativeImage> cir) throws IOException {
        if (NativeImageSnapshot.enabled()) {
            cir.setReturnValue(NativeImageSnapshot.read(input));
        }
    }
}
