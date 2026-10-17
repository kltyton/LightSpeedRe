package com.ccr4ft3r.lightspeed.mixin.registry;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegisterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Registers the known callback directly, avoiding annotation scans and ASM dispatcher wrappers. */
@Mixin(value = DeferredRegister.class, remap = false)
public abstract class DeferredRegisterMixin {
    @Shadow private void addEntries(RegisterEvent event) { throw new AssertionError(); }

    @WrapOperation(method = "register(Lnet/minecraftforge/eventbus/api/IEventBus;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/eventbus/api/IEventBus;register(Ljava/lang/Object;)V"))
    private void lightspeed$registerDirect(IEventBus bus, Object dispatcher, Operation<Void> original) {
        DeferredRegister<?> register = (DeferredRegister<?>) (Object) this;
        bus.addListener(EventPriority.NORMAL, false, RegisterEvent.class,
                BootstrapAgentBridge.keyedRegisterConsumer(register.getRegistryKey(), this::addEntries));
    }
}
