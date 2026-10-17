package com.ccr4ft3r.lightspeed.bootstrap.runtime.registry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Skips only DeferredRegister callbacks whose first action would reject the registry key. */
public final class KeyedRegisterRouting {
    private static final ConcurrentHashMap<Object, Object> KEYS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Dispatch> DISPATCH = new ThreadLocal<>();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final LongAdder EMPTY_BUSES = new LongAdder();

    private KeyedRegisterRouting() { }

    public static <T> Consumer<T> keyedConsumer(Object key, Consumer<T> action) {
        return new KeyedConsumer<>(key, action);
    }

    public static void withEvent(Object event, Object key, Runnable action) {
        Dispatch previous = DISPATCH.get();
        DISPATCH.set(new Dispatch(event, key));
        try {
            action.run();
        } finally {
            if (previous == null) DISPATCH.remove();
            else DISPATCH.set(previous);
        }
    }

    public static void record(Object target, Object listener) {
        if (target instanceof KeyedConsumer<?> keyed) KEYS.put(listener, keyed.key);
    }

    public static void forget(Object listener) {
        KEYS.remove(listener);
    }

    public static boolean skip(Object event, Object listener) {
        Dispatch active = DISPATCH.get();
        if (active == null || active.event != event) return false;
        Object key = KEYS.get(listener);
        if (key == null || active.key.equals(key)) return false;
        SKIPPED.increment();
        return true;
    }

    public static boolean skipBus(Object event, Object[] listeners) {
        Dispatch active = DISPATCH.get();
        if (active == null || active.event != event) return false;
        for (Object listener : listeners) {
            if (listener.getClass().getName().equals("net.minecraftforge.eventbus.api.EventPriority")) continue;
            Object key = KEYS.get(listener);
            if (key == null || active.key.equals(key)) return false;
        }
        EMPTY_BUSES.increment();
        return true;
    }

    public static long skipped() { return SKIPPED.sum(); }
    public static long emptyBuses() { return EMPTY_BUSES.sum(); }

    private record Dispatch(Object event, Object key) { }

    private record KeyedConsumer<T>(Object key, Consumer<T> action) implements Consumer<T> {
        @Override
        public void accept(T event) { action.accept(event); }
    }
}
