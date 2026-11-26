package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.texture.ParallelMipmapSupport;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Parallelizes the sprite mipmap loop inside SpriteLoader.stitch without targeting obfuscated
 * synthetic lambdas or relying on brittle local-variable ordinals.
 */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderMipmapMixin {

    @WrapOperation(
            method = "stitch",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/SpriteLoader;getStitchedSprites(Lnet/minecraft/client/renderer/texture/Stitcher;II)Ljava/util/Map;")
    )
    private Map<ResourceLocation, TextureAtlasSprite> lightspeed$captureStitchedSprites(
            SpriteLoader instance,
            Stitcher<SpriteContents> stitcher,
            int width,
            int height,
            Operation<Map<ResourceLocation, TextureAtlasSprite>> original,
            @Share("stitchedSprites") LocalRef<Map<ResourceLocation, TextureAtlasSprite>> stitchedSprites) {
        Map<ResourceLocation, TextureAtlasSprite> result = original.call(instance, stitcher, width, height);
        stitchedSprites.set(result);
        return result;
    }

    @WrapOperation(
            method = "stitch",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")
    )
    private CompletableFuture<Void> lightspeed$parallelMipmaps(
            Runnable runnable,
            Executor executor,
            Operation<CompletableFuture<Void>> original,
            @Local(argsOnly = true) List<SpriteContents> contents,
            @Local(argsOnly = true) int mipLevel,
            @Share("stitchedSprites") LocalRef<Map<ResourceLocation, TextureAtlasSprite>> stitchedSprites) {
        if (!ParallelMipmapSupport.enabled()) {
            return original.call(runnable, executor);
        }
        Map<ResourceLocation, TextureAtlasSprite> sprites = stitchedSprites.get();
        int effectiveMip = ParallelMipmapSupport.effectiveMipLevel(contents, mipLevel);
        if (sprites == null || sprites.isEmpty() || effectiveMip <= 0) {
            return original.call(runnable, executor);
        }
        return ParallelMipmapSupport.increaseMipLevelsAsync(sprites, effectiveMip);
    }
}
