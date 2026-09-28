package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.ShaderSourceIdentity;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.InputStream;
import java.util.List;

@Mixin(Program.class)
public abstract class ProgramSourceIdentityMixin {
    @Redirect(
            method = "compileShaderInternal",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;glShaderSource(ILjava/util/List;)V")
    )
    private static void lightspeed$recordProcessedSource(
            int shaderId,
            List<String> processedSources,
            Program.Type type,
            String name,
            InputStream input,
            String sourcePackId,
            GlslPreprocessor preprocessor) {
        ShaderSourceIdentity.record(type, name, processedSources);
        GlStateManager.glShaderSource(shaderId, processedSources);
    }
}
