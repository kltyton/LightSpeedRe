package com.ccr4ft3r.lightspeed.bootstrap.runtime.event;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class EventMethodCache {
    private static final ClassValue<ConcurrentHashMap<Method, Optional<Method>>> METHODS =
            new ClassValue<>() {
                @Override
                protected ConcurrentHashMap<Method, Optional<Method>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();

    private EventMethodCache() {
    }

    public static Optional<Method> declaredMethod(Class<?> type, Method inherited) {
        ConcurrentHashMap<Method, Optional<Method>> methods = METHODS.get(type);
        Optional<Method> cached = methods.get(inherited);
        if (cached != null) {
            HITS.increment();
            return cached;
        }
        MISSES.increment();
        return methods.computeIfAbsent(inherited, method -> find(type, method));
    }

    public static long hits() {
        return HITS.sum();
    }

    public static long misses() {
        return MISSES.sum();
    }

    private static Optional<Method> find(Class<?> type, Method inherited) {
        try {
            return Optional.of(type.getDeclaredMethod(inherited.getName(), inherited.getParameterTypes()));
        } catch (NoSuchMethodException exception) {
            return Optional.empty();
        }
    }
}
