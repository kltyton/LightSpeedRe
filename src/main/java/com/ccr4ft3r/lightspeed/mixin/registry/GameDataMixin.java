package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import com.ccr4ft3r.lightspeed.startup.registry.ObjectHolderDispatch;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.eventbus.api.Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

// Supply the registry being dispatched without changing the original predicate or callback order.
@Mixin(value = GameData.class, remap = false)
public abstract class GameDataMixin {
    @WrapOperation(method = "postRegisterEvents", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/fml/ModLoader;postEventWrapContainerInModOrder(Lnet/minecraftforge/eventbus/api/Event;)V"))
    private static void lightspeed$routeRegisterEvent(ModLoader loader, Event event, Operation<Void> original) {
        BootstrapAgentBridge.withRegisterEvent(event, ((RegisterEvent) event).getRegistryKey(),
                () -> original.call(loader, event));
    }

    @WrapOperation(method = "postRegisterEvents", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/registries/ObjectHolderRegistry;applyObjectHolders(Ljava/util/function/Predicate;)V"))
    private static void lightspeed$routeRegistryHolders(Predicate<ResourceLocation> filter, Operation<Void> original,
            @Local ResourceKey<?> registryKey) {
        ObjectHolderDispatch.withRegistryFilter(filter, registryKey.location(),
                () -> BootstrapAgentBridge.withRegistryFilter(filter, registryKey.location(), () -> original.call(filter)));
    }
}
