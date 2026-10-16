package com.ccr4ft3r.lightspeed.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Audits classes on the render thread before ModernFix resumes its background ClassInfo cleanup. */
@Pseudo
@Mixin(targets = "org.embeddedt.modernfix.util.ClassInfoManager", remap = false)
public abstract class ModernFixAuditMixin {
    @WrapOperation(method = "doClear", at = @At(value = "INVOKE",
            target = "Lorg/spongepowered/asm/mixin/MixinEnvironment;audit()V"), remap = false)
    private static void lightspeed$auditWithRenderContext(MixinEnvironment environment, Operation<Void> original) {
        if (RenderSystem.isOnRenderThread()) {
            original.call(environment);
        } else {
            Minecraft.getInstance().submit((Runnable) () -> original.call(environment)).join();
        }
    }
}
