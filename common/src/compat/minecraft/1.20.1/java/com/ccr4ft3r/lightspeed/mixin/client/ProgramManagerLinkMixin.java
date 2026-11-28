package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.ShaderProgramSnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.ProgramManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Restores or captures linked shader program binaries after {@code Shader#attachToProgram()}
 * has attached all shader stages (including geometry and tessellation stages).
 */
@Mixin(ProgramManager.class)
public abstract class ProgramManagerLinkMixin {
    @WrapOperation(
            method = "linkShader",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;glLinkProgram(I)V",
                    remap = false)
    )
    private static void lightspeed$restoreOrLinkAttachedProgram(int programId, Operation<Void> original) {
        ShaderProgramSnapshot.linkOrRestore(programId, () -> original.call(programId));
    }
}
