package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.registry.ObjectHolderDispatch;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ObjectHolderRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

// Index the ordinary Mod path while retaining unscoped holder dispatch.
@Mixin(value = ObjectHolderRegistry.class, remap = false)
public abstract class ObjectHolderRegistryMixin {
    @Shadow @Final private static Set<Consumer<Predicate<ResourceLocation>>> objectHolders;

    @WrapOperation(method = "addHandler", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private static boolean lightspeed$added(Set<Consumer<Predicate<ResourceLocation>>> holders,
                                            Object holder, Operation<Boolean> original) {
        boolean added = original.call(holders, holder);
        if (added) ObjectHolderDispatch.invalidate();
        return added;
    }

    @Inject(method = "removeHandler", at = @At("RETURN"))
    private static void lightspeed$removed(Consumer<Predicate<ResourceLocation>> holder,
                                          CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue()) ObjectHolderDispatch.invalidate();
    }

    @Inject(method = "applyObjectHolders(Ljava/util/function/Predicate;)V", at = @At("HEAD"), cancellable = true)
    private static void lightspeed$apply(Predicate<ResourceLocation> filter, CallbackInfo callback) {
        if (ObjectHolderDispatch.apply(objectHolders, filter)) callback.cancel();
    }
}
