package com.ccr4ft3r.lightspeed.startup.registry;

import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegisterEvent;

import java.util.function.Consumer;

/**
 * Registers keyed callbacks in their original event-bus order.
 */
public final class KeyedRegisterRouting {
    private KeyedRegisterRouting() {
    }

    public static void register(
            IEventBus bus,
            ResourceKey<?> registryKey,
            Consumer<RegisterEvent> listener
    ) {
        if (bus == null || registryKey == null || listener == null) {
            return;
        }
        Consumer<RegisterEvent> keyed = BootstrapAgentBridge.keyedRegisterConsumer(registryKey, listener);
        bus.addListener(EventPriority.NORMAL, false, RegisterEvent.class, keyed);
    }
}
