package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.TextureUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/** Sizes encoded texture buffers from archive entries before the native read loop. */
@Mixin(value = TextureUtil.class, remap = false)
public abstract class TextureResourceBufferMixin {
    @WrapOperation(method = "readResource(Ljava/io/InputStream;)Ljava/nio/ByteBuffer;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/TextureUtil;readResource(Ljava/nio/channels/ReadableByteChannel;I)Ljava/nio/ByteBuffer;"))
    private static ByteBuffer lightspeed$sizeArchiveBuffer(ReadableByteChannel channel, int capacity,
            Operation<ByteBuffer> original, @Local(argsOnly = true) InputStream input) throws IOException {
        if (GlobalCache.isEnabled) {
            Class<?> type = input.getClass();
            String name = type.getName();
            String module = type.getModule().getName();
            boolean sized = type == ByteArrayInputStream.class
                    || "jdk.zipfs".equals(module) && name.startsWith("jdk.nio.zipfs.ZipFileSystem$")
                    || "java.base".equals(module) && name.startsWith("java.util.zip.ZipFile$");
            if (sized) {
                int remaining = input.available();
                // The spare byte lets the original loop observe EOF without growing a full buffer.
                if (remaining > 0 && remaining < Integer.MAX_VALUE) capacity = remaining + 1;
            }
        }
        return original.call(channel, capacity);
    }
}
