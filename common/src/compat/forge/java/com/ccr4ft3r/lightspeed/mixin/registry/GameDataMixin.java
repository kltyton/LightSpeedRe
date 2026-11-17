package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.compat.ModPackPrewarmSupport;
import com.ccr4ft3r.lightspeed.startup.registry.ObjectHolderDispatch;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.registries.GameData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

// Supply the registry being dispatched without changing the original predicate or callback order.
@Mixin(value = GameData.class, remap = false)
public abstract class GameDataMixin {
    @Inject(method = "postRegisterEvents", at = @At("HEAD"), require = 0)
    private static void lightspeed$prewarmModPacksEarly(CallbackInfo ci) {
        ModPackPrewarmSupport.startAsyncPrewarm();
    }

    @WrapOperation(method = "postRegisterEvents", require = 0, at = @At(value = "INVOKE",
             target = "Lnet/minecraftforge/registries/ObjectHolderRegistry;applyObjectHolders(Ljava/util/function/Predicate;)V"))
    private static void lightspeed$routeRegistryHolders(Predicate<ResourceLocation> filter, Operation<Void> original,
            @Local ResourceKey<?> registryKey) {
        ObjectHolderDispatch.withRegistryFilter(filter, registryKey.location(), () -> original.call(filter));
    }
}
