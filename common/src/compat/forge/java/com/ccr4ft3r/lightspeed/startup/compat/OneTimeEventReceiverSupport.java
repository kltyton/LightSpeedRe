package com.ccr4ft3r.lightspeed.startup.compat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.EventBus;
import net.minecraftforge.eventbus.ListenerList;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.IEventListener;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Targeted event listener unregistration helper for Registrate's {@code OneTimeEventReceiver}.
 * Avoids {@code ListenerList.unregisterAll} scanning and locking every event class's ListenerList
 * across the entire game when cleaning up one-time listeners at load complete.
 */
public final class OneTimeEventReceiverSupport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RECEIVER_CLASS = "com.tterrag.registrate.util.OneTimeEventReceiver";
    private static final MethodHandle GET_BUS_ID;
    private static final MethodHandle GET_LISTENERS;

    static {
        MethodHandle busIdHandle = null;
        MethodHandle listenersHandle = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Field busIdField = EventBus.class.getDeclaredField("busID");
            busIdField.setAccessible(true);
            busIdHandle = lookup.unreflectGetter(busIdField)
                    .asType(MethodType.methodType(int.class, EventBus.class));

            Field listenersField = EventBus.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersHandle = lookup.unreflectGetter(listenersField)
                    .asType(MethodType.methodType(Map.class, EventBus.class));
        } catch (Throwable t) {
            LOGGER.warn("Failed to access EventBus internals; falling back to standard OneTimeEventReceiver unregister", t);
        }
        GET_BUS_ID = busIdHandle;
        GET_LISTENERS = listenersHandle;
    }

    private OneTimeEventReceiverSupport() {
    }

    public static void unregisterTargeted(Triple<?, ?, ?> triple) {
        if (triple == null) {
            return;
        }
        Object left = triple.getLeft();
        Object target = triple.getMiddle();
        Object right = triple.getRight();
        if (!(left instanceof IEventBus bus) || target == null) {
            return;
        }

        if (bus instanceof EventBus eventBus
                && right instanceof Class<?> eventClass
                && Event.class.isAssignableFrom(eventClass)
                && GET_BUS_ID != null
                && GET_LISTENERS != null) {
            try {
                int busId = (int) GET_BUS_ID.invokeExact(eventBus);
                @SuppressWarnings("unchecked")
                Map<Object, List<IEventListener>> listeners =
                        (Map<Object, List<IEventListener>>) GET_LISTENERS.invokeExact(eventBus);
                List<IEventListener> registered = listeners != null ? listeners.get(target) : null;
                if (registered != null && !registered.isEmpty()) {
                    if (RECEIVER_CLASS.equals(target.getClass().getName()) && registered.size() == 1) {
                        IEventListener listener = registered.get(0);
                        for (Class<?> cur = eventClass; cur != null && Event.class.isAssignableFrom(cur); cur = cur.getSuperclass()) {
                            EventListenerHelper.getListenerList(cur).unregister(busId, listener);
                        }
                    } else {
                        // Custom multi-listener target passed to public OneTimeEventReceiver.unregister
                        for (int i = 0; i < registered.size(); i++) {
                            ListenerList.unregisterAll(busId, registered.get(i));
                        }
                    }
                }
                if (registered != null) {
                    listeners.remove(target, registered);
                }
                EventListenerHelper.getListenerList(eventClass).getListeners(busId);
                return;
            } catch (Throwable t) {
                LOGGER.warn("Targeted OneTimeEventReceiver unregister failed, falling back to EventBus.unregister", t);
            }
        }

        unregisterStandard(bus, target, right);
    }

    private static void unregisterStandard(IEventBus bus, Object target, Object right) {
        bus.unregister(target);
        if (right instanceof Class<?> eventClass && bus instanceof EventBus eventBus && GET_BUS_ID != null) {
            try {
                int busId = (int) GET_BUS_ID.invokeExact(eventBus);
                EventListenerHelper.getListenerList(eventClass).getListeners(busId);
            } catch (Throwable t) {
                LOGGER.warn("Failed to clear listener list of one-time event receiver, so the receiver has leaked. This is not a big deal.", t);
            }
        }
    }
}
