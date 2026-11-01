package com.ccr4ft3r.lightspeed.mixin.compat.ldlib;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Optional;

/** Uses LDLib's existing missing value without throwing for absent metadata sections. */
@Pseudo
@Mixin(targets = "com.lowdragmc.lowdraglib.client.model.custommodel.LDLMetadataSection", remap = false)
public abstract class LdlibMetadataLookupMixin {
    @Inject(method = "getMetadata(Lnet/minecraft/resources/ResourceLocation;)Lcom/lowdragmc/lowdraglib/client/model/custommodel/LDLMetadataSection;",
            at = @At(value = "INVOKE", target = "Ljava/util/Optional;isPresent()Z"),
            locals = LocalCapture.CAPTURE_FAILHARD, remap = false, require = 1)
    private static void lightspeed$captureMissingMetadata(ResourceLocation texture,
            CallbackInfoReturnable<Object> callback, @Coerce Object missing, Optional<?> resource,
            @Share("lightspeed$missingLdlMetadata") LocalRef<Object> missingRef) {
        missingRef.set(missing);
    }

    @WrapOperation(method = "getMetadata(Lnet/minecraft/resources/ResourceLocation;)Lcom/lowdragmc/lowdraglib/client/model/custommodel/LDLMetadataSection;",
            at = @At(value = "INVOKE", target = "Ljava/util/Optional;get()Ljava/lang/Object;", ordinal = 1),
            remap = false, require = 1)
    private static Object lightspeed$readMetadataSection(Optional<?> section, Operation<Object> original,
            @Share("lightspeed$missingLdlMetadata") LocalRef<Object> missingRef) {
        return section.isEmpty() ? missingRef.get() : original.call(section);
    }
}
