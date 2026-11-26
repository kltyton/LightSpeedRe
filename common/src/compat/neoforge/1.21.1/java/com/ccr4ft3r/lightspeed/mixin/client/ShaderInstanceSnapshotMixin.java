package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.client.cache.assets.ShaderProgramSnapshot;
import com.ccr4ft3r.lightspeed.client.cache.assets.ShaderSourceIdentity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceSnapshotMixin {
    @Shadow @Final private VertexFormat vertexFormat;
    @Shadow @Final @Mutable private Program vertexProgram;
    @Shadow @Final @Mutable private Program fragmentProgram;

    @Unique private ResourceProvider lightspeed$provider;
    @Unique private String lightspeed$vertexName;
    @Unique private String lightspeed$vertexDigest;
    @Unique private Operation<Program> lightspeed$vertexOp;
    @Unique private String lightspeed$fragmentName;
    @Unique private String lightspeed$fragmentDigest;
    @Unique private Operation<Program> lightspeed$fragmentOp;

    @WrapOperation(
            method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ShaderInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;")
    )
    private Program lightspeed$deferShaderCompilation(
            ResourceProvider provider,
            Program.Type type,
            String name,
            Operation<Program> original) {
        if (((Object) this).getClass() != ShaderInstance.class || !ShaderProgramSnapshot.supported()) {
            return original.call(provider, type, name);
        }
        if (type == Program.Type.VERTEX) {
            String digest = ShaderSourceIdentity.rawDigest(provider, type, name);
            if (digest != null) {
                this.lightspeed$provider = provider;
                this.lightspeed$vertexName = name;
                this.lightspeed$vertexDigest = digest;
                this.lightspeed$vertexOp = original;
                return ShaderProgramSnapshot.createDummyProgram(type, name);
            }
            return original.call(provider, type, name);
        }
        if (type == Program.Type.FRAGMENT && this.lightspeed$vertexDigest != null) {
            String digest = ShaderSourceIdentity.rawDigest(provider, type, name);
            if (digest != null) {
                this.lightspeed$fragmentName = name;
                this.lightspeed$fragmentDigest = digest;
                this.lightspeed$fragmentOp = original;
                return ShaderProgramSnapshot.createDummyProgram(type, name);
            }
            this.vertexProgram = this.lightspeed$vertexOp.call(
                    this.lightspeed$provider, Program.Type.VERTEX, this.lightspeed$vertexName);
            lightspeed$clearDeferred();
        }
        return original.call(provider, type, name);
    }

    @WrapOperation(
            method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/shaders/ProgramManager;linkShader(Lcom/mojang/blaze3d/shaders/Shader;)V")
    )
    private void lightspeed$restoreProgramBinary(Shader shader, Operation<Void> original) {
        if (this.lightspeed$vertexDigest != null && this.lightspeed$fragmentDigest != null) {
            String vertexDigest = this.lightspeed$vertexDigest;
            String fragmentDigest = this.lightspeed$fragmentDigest;
            ResourceProvider provider = this.lightspeed$provider;
            String vertexName = this.lightspeed$vertexName;
            String fragmentName = this.lightspeed$fragmentName;
            Operation<Program> vertexOp = this.lightspeed$vertexOp;
            Operation<Program> fragmentOp = this.lightspeed$fragmentOp;
            lightspeed$clearDeferred();
            if (ShaderProgramSnapshot.restoreRaw(shader, vertexDigest, fragmentDigest, this.vertexFormat)) {
                return;
            }
            this.vertexProgram = vertexOp.call(provider, Program.Type.VERTEX, vertexName);
            this.fragmentProgram = fragmentOp.call(provider, Program.Type.FRAGMENT, fragmentName);
            ShaderProgramSnapshot.prepareCapture(shader);
            original.call(shader);
            ShaderProgramSnapshot.captureRaw(shader, vertexDigest, fragmentDigest, this.vertexFormat);
            return;
        }
        lightspeed$clearDeferred();
        original.call(shader);
    }

    @Unique
    private void lightspeed$clearDeferred() {
        this.lightspeed$provider = null;
        this.lightspeed$vertexName = null;
        this.lightspeed$vertexDigest = null;
        this.lightspeed$vertexOp = null;
        this.lightspeed$fragmentName = null;
        this.lightspeed$fragmentDigest = null;
        this.lightspeed$fragmentOp = null;
    }
}
