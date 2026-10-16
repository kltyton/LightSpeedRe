package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.ShaderProgramSnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceSnapshotMixin {
    @Shadow @Final private VertexFormat vertexFormat;
    @Shadow @Final private java.util.List<String> attributeNames;

    @WrapOperation(
            method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/shaders/ProgramManager;linkShader(Lcom/mojang/blaze3d/shaders/Shader;)V")
    )
    private void lightspeed$restoreProgramBinary(Shader shader, Operation<Void> original) {
        if (!ShaderProgramSnapshot.enabled()) {
            original.call(shader);
            return;
        }
        boolean bindAttributes = this.attributeNames != null;
        if (ShaderProgramSnapshot.restore(shader, this.vertexFormat, bindAttributes)) {
            return;
        }
        ShaderProgramSnapshot.prepareCapture(shader);
        original.call(shader);
        ShaderProgramSnapshot.capture(shader, this.vertexFormat, bindAttributes);
    }
}
