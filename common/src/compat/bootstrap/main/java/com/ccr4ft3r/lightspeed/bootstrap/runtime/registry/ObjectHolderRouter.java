package com.ccr4ft3r.lightspeed.bootstrap.runtime.registry;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ObjectHolderRouter {
    private static final AtomicLong VERSION = new AtomicLong();
    private static final LongAdder ROUTED_CALLS = new LongAdder();
    private static final LongAdder FALLBACK_CALLS = new LongAdder();
    private static final LongAdder SKIPPED_HANDLERS = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final ThreadLocal<FilterBinding> FILTER = new ThreadLocal<>();
    private static final Set<ClassLoader> REGISTRY_OBJECT_LOADERS = ConcurrentHashMap.newKeySet();
    private static final ClassValue<HandlerAccess> HANDLER_ACCESS = new ClassValue<>() {
        @Override
        protected HandlerAccess computeValue(Class<?> type) {
            boolean registryObject = type.getName().equals("net.minecraftforge.registries.RegistryObject$1");
            if (registryObject && !REGISTRY_OBJECT_LOADERS.contains(type.getClassLoader())) {
                return HandlerAccess.UNSUPPORTED;
            }
            if (!registryObject && !"ObjectHolderRef".equals(type.getSimpleName())) {
                return HandlerAccess.UNSUPPORTED;
            }
            try {
                RuntimeModuleAccess.openToAgent(type);
                if (registryObject) {
                    Field key = accessibleField(type, "val$registryName");
                    Field owner = accessibleField(type, "this$0");
                    ReferenceValidation validation = new ReferenceValidation(
                            accessibleField(type, "registryExists"), accessibleField(type, "invalidRegistry"),
                            owner, accessibleField(owner.getType(), "optionalRegistry"));
                    return new HandlerAccess(key, null, validation);
                }
                Field registry = type.getDeclaredField("registry");
                registry.setAccessible(true);
                Method registryName = findRegistryName(registry.getType());
                registryName.setAccessible(true);
                return new HandlerAccess(registry, registryName, null);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                FAILURES.increment();
                return HandlerAccess.UNSUPPORTED;
            }
        }
    };
    private static final OrderedHandler[] EMPTY_HANDLERS = new OrderedHandler[0];
    private static volatile Routing routing = Routing.EMPTY;

    private ObjectHolderRouter() {
    }

    public static void invalidate() {
        VERSION.incrementAndGet();
    }

    public static void observeRegistryObject(ClassLoader loader, Class<?> redefined, boolean supported) {
        if (loader == null) return;
        if (supported) REGISTRY_OBJECT_LOADERS.add(loader);
        else REGISTRY_OBJECT_LOADERS.remove(loader);
        if (redefined != null) HANDLER_ACCESS.remove(redefined);
        invalidate();
    }

    public static void apply(Set<?> handlers, Predicate<?> filter) {
        FilterBinding binding = FILTER.get();
        if (binding == null || binding.filter() != filter) {
            FALLBACK_CALLS.increment();
            invoke(List.copyOf(handlers), filter);
            return;
        }
        applyForKey(handlers, filter, binding.key());
    }

    public static void withRegistryFilter(Predicate<?> filter, Object key, Runnable action) {
        FilterBinding previous = FILTER.get();
        FILTER.set(new FilterBinding(filter, key));
        try {
            action.run();
        } finally {
            if (previous == null) FILTER.remove();
            else FILTER.set(previous);
        }
    }

    static void applyForKey(Set<?> handlers, Predicate<?> filter, Object key) {
        Routing current = routing(handlers);
        OrderedHandler[] own = current.byRegistry().getOrDefault(key, EMPTY_HANDLERS);
        OrderedHandler[] custom = current.fallbackCustom();
        ROUTED_CALLS.increment();
        SKIPPED_HANDLERS.add(Math.max(0, current.handlerCount() - own.length - custom.length));
        invokeMerged(own, custom, filter);
    }

    public static long routedCalls() {
        return ROUTED_CALLS.sum();
    }

    public static long fallbackCalls() {
        return FALLBACK_CALLS.sum();
    }

    public static long skippedHandlers() {
        return SKIPPED_HANDLERS.sum();
    }

    public static long failures() {
        return FAILURES.sum();
    }

    private static Routing routing(Set<?> handlers) {
        long version = VERSION.get();
        Routing current = routing;
        if (current.version() == version) {
            if (current.pending().length == 0 || !hasNewlyValidated(current.pending())) {
                return current;
            }
        }
        synchronized (ObjectHolderRouter.class) {
            version = VERSION.get();
            current = routing;
            if (current.version() == version) {
                if (current.pending().length == 0) {
                    return current;
                }
                Routing promoted = promotePending(current);
                routing = promoted;
                return promoted;
            }
            Object[] snapshot = handlers.toArray();
            Map<Object, List<OrderedHandler>> grouped = new LinkedHashMap<>();
            List<OrderedHandler> staticCustom = new ArrayList<>();
            List<OrderedHandler> pending = new ArrayList<>();
            for (int index = 0; index < snapshot.length; index++) {
                Object handler = snapshot[index];
                Object key = registryKey(handler);
                OrderedHandler ordered = new OrderedHandler(index, handler);
                HandlerAccess access = HANDLER_ACCESS.get(handler.getClass());
                if (key == null) {
                    if (access.validation() != null) {
                        pending.add(ordered);
                    } else {
                        staticCustom.add(ordered);
                    }
                } else {
                    grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ordered);
                }
            }
            Map<Object, OrderedHandler[]> routes = new LinkedHashMap<>(grouped.size() * 2 + 1);
            grouped.forEach((k, own) -> routes.put(k, own.toArray(EMPTY_HANDLERS)));
            OrderedHandler[] staticArr = staticCustom.toArray(EMPTY_HANDLERS);
            OrderedHandler[] pendingArr = pending.toArray(EMPTY_HANDLERS);
            OrderedHandler[] fallbackCustom = mergeArrays(staticArr, pendingArr);
            current = new Routing(version, snapshot.length, Map.copyOf(routes),
                    staticArr, pendingArr, fallbackCustom);
            routing = current;
            return current;
        }
    }

    private static boolean hasNewlyValidated(OrderedHandler[] pending) {
        for (OrderedHandler ordered : pending) {
            if (registryKey(ordered.handler()) != null) {
                return true;
            }
        }
        return false;
    }

    private static Routing promotePending(Routing current) {
        OrderedHandler[] oldPending = current.pending();
        Map<Object, List<OrderedHandler>> newlyGrouped = null;
        List<OrderedHandler> stillPending = null;
        for (int i = 0; i < oldPending.length; i++) {
            OrderedHandler ordered = oldPending[i];
            Object key = registryKey(ordered.handler());
            if (key == null) {
                if (stillPending != null) {
                    stillPending.add(ordered);
                }
            } else {
                if (newlyGrouped == null) {
                    newlyGrouped = new LinkedHashMap<>();
                    stillPending = new ArrayList<>(Math.max(0, oldPending.length - i - 1));
                    for (int prev = 0; prev < i; prev++) {
                        stillPending.add(oldPending[prev]);
                    }
                }
                newlyGrouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ordered);
            }
        }
        if (newlyGrouped == null) {
            return current;
        }
        Map<Object, OrderedHandler[]> updated = new LinkedHashMap<>(current.byRegistry());
        newlyGrouped.forEach((key, additions) -> {
            OrderedHandler[] addArr = additions.toArray(EMPTY_HANDLERS);
            OrderedHandler[] existing = updated.get(key);
            updated.put(key, existing == null ? addArr : mergeArrays(existing, addArr));
        });
        OrderedHandler[] newPending = stillPending.toArray(EMPTY_HANDLERS);
        OrderedHandler[] newFallback = mergeArrays(current.staticCustom(), newPending);
        return new Routing(current.version(), current.handlerCount(), Map.copyOf(updated),
                current.staticCustom(), newPending, newFallback);
    }

    private static OrderedHandler[] mergeArrays(OrderedHandler[] first, OrderedHandler[] second) {
        if (first.length == 0) return second;
        if (second.length == 0) return first;
        OrderedHandler[] merged = new OrderedHandler[first.length + second.length];
        int i = 0;
        int j = 0;
        int out = 0;
        while (i < first.length && j < second.length) {
            if (first[i].position() < second[j].position()) {
                merged[out++] = first[i++];
            } else {
                merged[out++] = second[j++];
            }
        }
        if (i < first.length) {
            System.arraycopy(first, i, merged, out, first.length - i);
        } else if (j < second.length) {
            System.arraycopy(second, j, merged, out, second.length - j);
        }
        return merged;
    }

    private record OrderedHandler(int position, Object handler) { }

    @SuppressWarnings("unchecked")
    private static void invokeMerged(OrderedHandler[] own, OrderedHandler[] custom, Predicate<?> filter) {
        RuntimeException aggregate = new RuntimeException(
                "Failed to apply some object holders, see suppressed exceptions for details");
        int i = 0;
        int j = 0;
        while (i < own.length || j < custom.length) {
            OrderedHandler next;
            if (j == custom.length || i < own.length && own[i].position() < custom[j].position()) {
                next = own[i++];
            } else {
                next = custom[j++];
            }
            try {
                ((Consumer<Object>) next.handler()).accept(filter);
            } catch (Exception exception) {
                aggregate.addSuppressed(exception);
            }
        }
        if (aggregate.getSuppressed().length > 0) {
            throw aggregate;
        }
    }

    @SuppressWarnings("unchecked")
    private static void invoke(List<Object> handlers, Predicate<?> filter) {
        RuntimeException aggregate = new RuntimeException(
                "Failed to apply some object holders, see suppressed exceptions for details");
        for (Object handler : handlers) {
            try {
                ((Consumer<Object>) handler).accept(filter);
            } catch (Exception exception) {
                aggregate.addSuppressed(exception);
            }
        }
        if (aggregate.getSuppressed().length > 0) {
            throw aggregate;
        }
    }

    private static Object registryKey(Object handler) {
        HandlerAccess access = HANDLER_ACCESS.get(handler.getClass());
        if (access == HandlerAccess.UNSUPPORTED) {
            return null;
        }
        try {
            if (access.validation() != null && !access.validation().ready(handler)) return null;
            Object registry = access.registry().get(handler);
            return registry == null || access.registryName() == null ? registry : access.registryName().invoke(registry);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            FAILURES.increment();
            return null;
        }
    }

    private static Field accessibleField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method findRegistryName(Class<?> type) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod("getRegistryName");
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + ".getRegistryName");
    }

    private record HandlerAccess(Field registry, Method registryName, ReferenceValidation validation) {
        private static final HandlerAccess UNSUPPORTED = new HandlerAccess(null, null, null);
    }

    private record ReferenceValidation(Field exists, Field invalid, Field owner, Field optional) {
        private boolean ready(Object handler) throws IllegalAccessException {
            return exists.getBoolean(handler) || invalid.getBoolean(handler) || optional.getBoolean(owner.get(handler));
        }
    }

    private record FilterBinding(Predicate<?> filter, Object key) { }

    private record Routing(long version, int handlerCount, Map<Object, OrderedHandler[]> byRegistry,
                           OrderedHandler[] staticCustom, OrderedHandler[] pending, OrderedHandler[] fallbackCustom) {
        private static final Routing EMPTY = new Routing(-1, 0, Map.of(), EMPTY_HANDLERS, EMPTY_HANDLERS, EMPTY_HANDLERS);
    }
}
