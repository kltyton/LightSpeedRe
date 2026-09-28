package com.ccr4ft3r.lightspeed.bootstrap.runtime.transform;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class MixinTargetIndex {
    private static final AtomicLong REVISION = new AtomicLong();
    private static final LongAdder QUERIES = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final Set<ClassLoader> TRACKED_LOADERS = Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile boolean trackingInstalled;
    private static final ClassValue<Method> TARGETS = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                RuntimeModuleAccess.openToAgent(type);
                Method method = type.getDeclaredMethod("getTargets");
                if (!method.trySetAccessible()) {
                    throw new IllegalStateException("Mixin targets are inaccessible");
                }
                return method;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("unsupported Mixin configuration", exception);
            }
        }
    };
    private static final ClassValue<Method> PACKAGES = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                Method method = type.getDeclaredMethod("getMixinPackage");
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    };

    private MixinTargetIndex() {
    }

    public static void trackingInstalled(ClassLoader loader) {
        synchronized (TRACKED_LOADERS) {
            TRACKED_LOADERS.add(loader);
        }
        trackingInstalled = true;
        invalidate();
    }

    public static void invalidate() {
        REVISION.incrementAndGet();
        ClassPassThrough.invalidate();
    }

    static long revision() {
        return REVISION.get();
    }

    public static Object create() {
        return new State();
    }

    // The caller holds its MixinProcessor monitor, including during recursive class loads.
    public static List<?> select(List<?> configurations, Object value, String className) {
        if (!trackingInstalled || !(value instanceof State state) || state.building || state.disabled) {
            return configurations;
        }
        long revision = REVISION.get();
        if (state.revision != revision || state.configurationCount != configurations.size()) {
            state.building = true;
            try {
                Map<String, List<Object>> grouped = new HashMap<>();
                List<String> packages = new ArrayList<>();
                for (Object configuration : configurations) {
                    synchronized (TRACKED_LOADERS) {
                        if (!TRACKED_LOADERS.contains(configuration.getClass().getClassLoader())) {
                            return configurations;
                        }
                    }
                    Object result = TARGETS.get(configuration.getClass()).invoke(configuration);
                    packages.add((String) PACKAGES.get(configuration.getClass()).invoke(configuration));
                    state.loader = configuration.getClass().getClassLoader();
                    if (!(result instanceof Set<?> targets)) {
                        throw new IllegalStateException("unexpected Mixin target set");
                    }
                    for (Object target : targets) {
                        if (!(target instanceof String name)) {
                            throw new IllegalStateException("unexpected Mixin target name");
                        }
                        grouped.computeIfAbsent(name, ignored -> new ArrayList<>()).add(configuration);
                    }
                }
                if (revision != REVISION.get()) {
                    return configurations;
                }
                Map<String, List<?>> indexed = new HashMap<>();
                grouped.forEach((name, matches) -> indexed.put(name, List.copyOf(matches)));
                state.targets = indexed;
                state.packages = packages;
                state.configurationCount = configurations.size();
                state.revision = revision;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                state.disabled = true;
                FAILURES.increment();
                System.err.println("[Lightspeed Agent] Mixin target index disabled: " + exception);
                return configurations;
            } finally {
                state.building = false;
            }
        }
        List<?> matches = state.targets.getOrDefault(className, List.of());
        QUERIES.increment();
        SKIPPED.add(configurations.size() - matches.size());
        return matches;
    }

    public static long queries() {
        return QUERIES.sum();
    }

    public static long skipped() {
        return SKIPPED.sum();
    }

    public static long failures() {
        return FAILURES.sum();
    }

    public static void publish(Object value, Object environment, Object coprocessors, Object hotSwapper, List<?> generators) {
        State state = (State) value;
        if (hotSwapper != null || state.disabled || state.revision != REVISION.get()
                || state.publishedRevision == state.revision) return;
        try {
            if (ClassPassThrough.publish(state.loader, environment, state.targets.keySet(), state.packages,
                    (Iterable<?>) coprocessors, generators, state.revision)) {
                state.publishedRevision = state.revision;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            state.disabled = true;
            FAILURES.increment();
            System.err.println("[Lightspeed Agent] Mixin class eligibility unavailable: " + exception);
        }
    }

    private static final class State {
        private long revision = -1;
        private int configurationCount = -1;
        private Map<String, List<?>> targets = Map.of();
        private List<String> packages = List.of();
        private ClassLoader loader;
        private long publishedRevision = -1;
        private boolean building;
        private boolean disabled;
    }
}
