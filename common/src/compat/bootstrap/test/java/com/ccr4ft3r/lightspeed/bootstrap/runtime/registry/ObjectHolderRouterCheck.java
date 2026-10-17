package com.ccr4ft3r.lightspeed.bootstrap.runtime.registry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ObjectHolderRouterCheck {
    private ObjectHolderRouterCheck() {
    }

    public static void main(String[] args) {
        List<String> calls = new ArrayList<>();
        Set<Object> handlers = new LinkedHashSet<>();
        handlers.add(new ObjectHolderRef("first", calls));
        handlers.add((Consumer<Predicate<Object>>) filter -> calls.add("custom"));
        handlers.add(new ObjectHolderRef("second", calls));
        Predicate<Object> first = "first"::equals;

        ObjectHolderRouter.invalidate();
        ObjectHolderRouter.applyForKey(handlers, first, "first");
        require(calls.equals(List.of("first", "custom")), "single-registry route changed handler order");

        calls.clear();
        ObjectHolderRouter.apply(handlers, ignored -> true);
        require(calls.equals(List.of("first", "custom", "second")), "fallback route changed full iteration");

        calls.clear();
        handlers.add(new ObjectHolderRef("second", calls, "second-late"));
        ObjectHolderRouter.invalidate();
        ObjectHolderRouter.applyForKey(handlers, "second"::equals, "second");
        require(calls.equals(List.of("custom", "second", "second-late")),
                "mutation invalidation retained a stale route");
        require(ObjectHolderRouter.skippedHandlers() > 0, "routing did not skip unrelated handlers");
        System.out.println("OBJECT_HOLDER_ROUTER_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeRegistry {
        private final Object name;

        private FakeRegistry(Object name) {
            this.name = name;
        }

        @SuppressWarnings("unused")
        public Object getRegistryName() {
            return name;
        }
    }

    private static final class ObjectHolderRef implements Consumer<Predicate<Object>> {
        @SuppressWarnings("unused")
        private final FakeRegistry registry;
        private final List<String> calls;
        private final String label;

        private ObjectHolderRef(Object registry, List<String> calls) {
            this(registry, calls, registry.toString());
        }

        private ObjectHolderRef(Object registry, List<String> calls, String label) {
            this.registry = new FakeRegistry(registry);
            this.calls = calls;
            this.label = label;
        }

        @Override
        public void accept(Predicate<Object> filter) {
            if (filter.test(registry.getRegistryName())) {
                calls.add(label);
            }
        }
    }
}
