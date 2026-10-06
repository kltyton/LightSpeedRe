package com.ccr4ft3r.lightspeed.startup.registry;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ObjectHolderDispatch {
    private static final AtomicLong VERSION = new AtomicLong();
    private static final ThreadLocal<Binding> FILTER = new ThreadLocal<>();
    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final LongAdder NANOS = new LongAdder();
    private static volatile Routes routes;

    private ObjectHolderDispatch() {
    }

    public static void invalidate() {
        VERSION.incrementAndGet();
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
        List<OrderedHolder> selected =
                current.byRegistry().getOrDefault(binding.key(), current.custom());
        CALLS.increment();
        SKIPPED.add(current.count() - selected.size());
        RuntimeException aggregate = new RuntimeException(
                "Failed to apply some object holders, see suppressed exceptions for details");
        try {
            for (OrderedHolder ordered : selected) {
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
            if (current.awaitingValidation()) routes = null;
            NANOS.add(System.nanoTime() - start);
        }
    }

    private static Routes routes(Set<Consumer<Predicate<ResourceLocation>>> holders) {
        long version = VERSION.get();
        Routes current = routes;
        if (current != null && current.version() == version) return current;
        synchronized (ObjectHolderDispatch.class) {
            current = routes;
            if (current != null && current.version() == version) return current;
            List<Consumer<Predicate<ResourceLocation>>> snapshot = new ArrayList<>(holders);
            Map<ResourceLocation, List<OrderedHolder>> grouped = new LinkedHashMap<>();
            List<OrderedHolder> custom = new ArrayList<>();
            boolean awaitingValidation = false;
            for (int index = 0; index < snapshot.size(); index++) {
                Consumer<Predicate<ResourceLocation>> holder = snapshot.get(index);
                ResourceLocation key = holder instanceof RegistryHolder keyed
                        ? keyed.lightspeed$validatedRegistryName() : null;
                OrderedHolder ordered = new OrderedHolder(index, holder);
                if (key == null) {
                    custom.add(ordered);
                    awaitingValidation |= holder instanceof RegistryHolder;
                } else {
                    grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ordered);
                }
            }
            Map<ResourceLocation, List<OrderedHolder>> indexed = new LinkedHashMap<>();
            grouped.forEach((key, own) -> indexed.put(key, merge(own, custom)));
            current = new Routes(version, snapshot.size(), Map.copyOf(indexed), List.copyOf(custom), awaitingValidation);
            routes = current;
            return current;
        }
    }

    private static List<OrderedHolder> merge(List<OrderedHolder> own, List<OrderedHolder> custom) {
        List<OrderedHolder> selected = new ArrayList<>(own.size() + custom.size());
        int ownIndex = 0;
        int customIndex = 0;
        while (ownIndex < own.size() || customIndex < custom.size()) {
            if (customIndex == custom.size()
                    || ownIndex < own.size() && own.get(ownIndex).position() < custom.get(customIndex).position()) {
                selected.add(own.get(ownIndex++));
            } else {
                selected.add(custom.get(customIndex++));
            }
        }
        return List.copyOf(selected);
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
                          Map<ResourceLocation, List<OrderedHolder>> byRegistry,
                          List<OrderedHolder> custom, boolean awaitingValidation) { }
}
