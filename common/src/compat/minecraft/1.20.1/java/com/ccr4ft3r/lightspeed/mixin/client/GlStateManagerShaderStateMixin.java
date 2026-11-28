package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.ShaderProgramSnapshot;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks explicit vertex attribute bindings and deletion for active shader programs so
 * custom attribute remappings are reflected in shader binary snapshot keys.
 */
@Mixin(value = GlStateManager.class, remap = false)
public abstract class GlStateManagerShaderStateMixin {
    @Inject(method = "_glBindAttribLocation(IILjava/lang/CharSequence;)V", at = @At("HEAD"))
    private static void lightspeed$recordBindAttribLocation(
            int program, int index, CharSequence name, CallbackInfo ci) {
        ShaderProgramSnapshot.recordBoundAttribute(program, index, name);
    }

    @Inject(method = "glDeleteProgram(I)V", at = @At("HEAD"))
    private static void lightspeed$forgetDeletedProgram(int program, CallbackInfo ci) {
        ShaderProgramSnapshot.forgetProgram(program);
    }
}
