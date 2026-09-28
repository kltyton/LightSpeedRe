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
        List<Object> selected = current.byRegistry().getOrDefault(key, current.customHandlers());
        ROUTED_CALLS.increment();
        SKIPPED_HANDLERS.add(Math.max(0, current.handlerCount() - selected.size()));
        try {
            invoke(selected, filter);
        } finally {
            if (current.awaitingValidation()) invalidate();
        }
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
            return current;
        }
        synchronized (ObjectHolderRouter.class) {
            current = routing;
            if (current.version() == version) {
                return current;
            }
            List<Object> snapshot = List.copyOf(handlers);
            Map<Object, List<OrderedHandler>> grouped = new LinkedHashMap<>();
            List<OrderedHandler> custom = new ArrayList<>();
            boolean awaitingValidation = false;
            for (int index = 0; index < snapshot.size(); index++) {
                Object handler = snapshot.get(index);
                Object key = registryKey(handler);
                OrderedHandler ordered = new OrderedHandler(index, handler);
                HandlerAccess access = HANDLER_ACCESS.get(handler.getClass());
                if (key == null && access.validation() != null) awaitingValidation = true;
                if (key == null) {
                    custom.add(ordered);
                } else {
                    grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ordered);
                }
            }
            Map<Object, List<Object>> routes = new LinkedHashMap<>();
            grouped.forEach((key, own) -> routes.put(key, mergeHandlers(own, custom)));
            current = new Routing(version, snapshot.size(), Map.copyOf(routes),
                    custom.stream().map(OrderedHandler::handler).toList(), awaitingValidation);
            routing = current;
            return current;
        }
    }

    private static List<Object> mergeHandlers(List<OrderedHandler> own, List<OrderedHandler> custom) {
        List<Object> selected = new ArrayList<>(own.size() + custom.size());
        int ownIndex = 0;
        int customIndex = 0;
        while (ownIndex < own.size() || customIndex < custom.size()) {
            if (customIndex == custom.size()
                    || ownIndex < own.size() && own.get(ownIndex).position() < custom.get(customIndex).position()) {
                selected.add(own.get(ownIndex++).handler());
            } else {
                selected.add(custom.get(customIndex++).handler());
            }
        }
        return List.copyOf(selected);
    }

    private record OrderedHandler(int position, Object handler) { }

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

    private record Routing(long version, int handlerCount, Map<Object, List<Object>> byRegistry,
                           List<Object> customHandlers, boolean awaitingValidation) {
        private static final Routing EMPTY = new Routing(-1, 0, Map.of(), List.of(), false);
    }
}
