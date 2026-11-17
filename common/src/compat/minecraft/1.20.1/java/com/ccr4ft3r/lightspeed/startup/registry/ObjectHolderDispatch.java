package com.ccr4ft3r.lightspeed.startup.registry;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ObjectHolderDispatch {
    private static final OrderedHolder[] EMPTY_HOLDERS = new OrderedHolder[0];
    private static final AtomicLong VERSION = new AtomicLong();
    private static final ThreadLocal<Binding> FILTER = new ThreadLocal<>();
    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final LongAdder NANOS = new LongAdder();
    private static volatile Routes routes;
    private static boolean needsFullRebuild = true;

    private ObjectHolderDispatch() {
    }

    public static void invalidate() {
        synchronized (ObjectHolderDispatch.class) {
            needsFullRebuild = true;
            VERSION.incrementAndGet();
        }
    }

    public static void onAdded(Object holder) {
        synchronized (ObjectHolderDispatch.class) {
            VERSION.incrementAndGet();
            needsFullRebuild = true;
        }
    }

    public static void withRegistryFilter(Predicate<ResourceLocation> filter, ResourceLocation key, Runnable action) {
        Binding previous = FILTER.get();
        FILTER.set(new Binding(filter, key));
        try {
            action.run();
        } finally {
            if (previous == null) FILTER.remove();
            else FILTER.set(previous);
        }
    }

    public static boolean apply(Set<Consumer<Predicate<ResourceLocation>>> holders,
                                Predicate<ResourceLocation> filter) {
        Binding binding = FILTER.get();
        if (binding == null || binding.filter() != filter) return false;
        long start = System.nanoTime();
        Routes current = routes(holders);
        OrderedHolder[] own = current.byRegistry().getOrDefault(binding.key(), EMPTY_HOLDERS);
        OrderedHolder[] custom = current.fallbackCustom();
        CALLS.increment();
        SKIPPED.add(current.count() - own.length - custom.length);
        RuntimeException aggregate = new RuntimeException(
                "Failed to apply some object holders, see suppressed exceptions for details");
        try {
            int ownIndex = 0;
            int customIndex = 0;
            while (ownIndex < own.length || customIndex < custom.length) {
                OrderedHolder ordered;
                if (customIndex == custom.length
                        || ownIndex < own.length && own[ownIndex].position() < custom[customIndex].position()) {
                    ordered = own[ownIndex++];
                } else {
                    ordered = custom[customIndex++];
                }
                try {
                    ordered.holder().accept(filter);
                } catch (Exception exception) {
                    aggregate.addSuppressed(exception);
                }
                // The original HashSet iterator detects mutation before its next element.
                if (ordered.position() + 1 < current.count() && VERSION.get() != current.version()) {
                    throw new ConcurrentModificationException();
                }
            }
            if (aggregate.getSuppressed().length > 0) throw aggregate;
            return true;
        } finally {
            NANOS.add(System.nanoTime() - start);
        }
    }

    @SuppressWarnings("unchecked")
    private static Routes routes(Set<Consumer<Predicate<ResourceLocation>>> holders) {
        long version = VERSION.get();
        Routes current = routes;
        if (current != null && current.version() == version) {
            if (current.pending().length == 0 || !hasNewlyValidated(current.pending())) {
                return current;
            }
        }
        synchronized (ObjectHolderDispatch.class) {
            version = VERSION.get();
            current = routes;
            if (current != null && !needsFullRebuild) {
                if (current.pending().length != 0 && hasNewlyValidated(current.pending())) {
                    current = promotePending(current, version);
                } else if (current.version() != version) {
                    current = current.withVersion(version);
                }
                routes = current;
                return current;
            }
            Object[] snapshot = holders.toArray();
            Map<ResourceLocation, List<OrderedHolder>> grouped = new HashMap<>(64);
            List<OrderedHolder> staticCustom = new ArrayList<>();
            List<OrderedHolder> pending = new ArrayList<>();
            classifyHolders(snapshot, snapshot.length, 0, grouped, staticCustom, pending);
            Map<ResourceLocation, OrderedHolder[]> byRegistry = new HashMap<>(Math.max(16, grouped.size() * 2 + 1));
            grouped.forEach((key, own) -> byRegistry.put(key, own.toArray(EMPTY_HOLDERS)));
            OrderedHolder[] staticArr = staticCustom.toArray(EMPTY_HOLDERS);
            OrderedHolder[] pendingArr = pending.toArray(EMPTY_HOLDERS);
            OrderedHolder[] fallbackCustom = mergeArrays(staticArr, pendingArr);
            current = new Routes(version, snapshot.length, Collections.unmodifiableMap(byRegistry),
                    staticArr, pendingArr, fallbackCustom);
            needsFullRebuild = false;
            routes = current;
            return current;
        }
    }

    @SuppressWarnings("unchecked")
    private static void classifyHolders(
            Object[] items,
            int length,
            int basePosition,
            Map<ResourceLocation, List<OrderedHolder>> grouped,
            List<OrderedHolder> staticCustom,
            List<OrderedHolder> pending
    ) {
        for (int index = 0; index < length; index++) {
            Consumer<Predicate<ResourceLocation>> holder =
                    (Consumer<Predicate<ResourceLocation>>) items[index];
            OrderedHolder ordered = new OrderedHolder(basePosition + index, holder);
            if (holder instanceof RegistryHolder keyed) {
                ResourceLocation key = keyed.lightspeed$validatedRegistryName();
                if (key == null) {
                    pending.add(ordered);
                } else {
                    grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ordered);
                }
            } else {
                staticCustom.add(ordered);
            }
        }
    }

    private static boolean hasNewlyValidated(OrderedHolder[] pending) {
        for (OrderedHolder ordered : pending) {
            if (((RegistryHolder) ordered.holder()).lightspeed$validatedRegistryName() != null) {
                return true;
            }
        }
        return false;
    }

    private static Routes promotePending(Routes current, long version) {
        OrderedHolder[] oldPending = current.pending();
        Map<ResourceLocation, List<OrderedHolder>> newlyGrouped = null;
        List<OrderedHolder> stillPending = null;
        for (int i = 0; i < oldPending.length; i++) {
            OrderedHolder ordered = oldPending[i];
            ResourceLocation key = ((RegistryHolder) ordered.holder()).lightspeed$validatedRegistryName();
            if (key == null) {
                if (stillPending != null) {
                    stillPending.add(ordered);
                }
            } else {
                if (newlyGrouped == null) {
                    newlyGrouped = new HashMap<>();
                    stillPending = new ArrayList<>(Math.max(0, oldPending.length - i - 1));
                    for (int prev = 0; prev < i; prev++) {
                        stillPending.add(oldPending[prev]);
                    }
                }
                newlyGrouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ordered);
            }
        }
        if (newlyGrouped == null) {
            return current.version() == version ? current : current.withVersion(version);
        }
        Map<ResourceLocation, OrderedHolder[]> updatedByRegistry = new HashMap<>(current.byRegistry().size() * 2 + 4);
        updatedByRegistry.putAll(current.byRegistry());
        newlyGrouped.forEach((key, additions) -> {
            OrderedHolder[] addArr = additions.toArray(EMPTY_HOLDERS);
            OrderedHolder[] existing = updatedByRegistry.get(key);
            updatedByRegistry.put(key, existing == null ? addArr : mergeArrays(existing, addArr));
        });
        OrderedHolder[] newPending = stillPending.toArray(EMPTY_HOLDERS);
        OrderedHolder[] newFallback = mergeArrays(current.staticCustom(), newPending);
        return new Routes(version, current.count(), Collections.unmodifiableMap(updatedByRegistry),
                current.staticCustom(), newPending, newFallback);
    }

    private static OrderedHolder[] mergeArrays(OrderedHolder[] first, OrderedHolder[] second) {
        if (first.length == 0) return second;
        if (second.length == 0) return first;
        OrderedHolder[] merged = new OrderedHolder[first.length + second.length];
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

    public static long calls() {
        return CALLS.sum();
    }

    public static long skipped() {
        return SKIPPED.sum();
    }

    public static long elapsedMillis() {
        return NANOS.sum() / 1_000_000L;
    }

    private record Binding(Predicate<ResourceLocation> filter, ResourceLocation key) { }
    private record OrderedHolder(int position, Consumer<Predicate<ResourceLocation>> holder) { }
    private record Routes(long version, int count,
                          Map<ResourceLocation, OrderedHolder[]> byRegistry,
                          OrderedHolder[] staticCustom,
                          OrderedHolder[] pending,
                          OrderedHolder[] fallbackCustom) {
        private Routes withVersion(long nextVersion) {
            return new Routes(nextVersion, count, byRegistry, staticCustom, pending, fallbackCustom);
        }
    }
}
