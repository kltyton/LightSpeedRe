package com.ccr4ft3r.lightspeed.api.startup;

import java.util.Set;

/**
 * Declares opt-in startup work and the effects needed to order it safely.
 * Effect names are opaque identifiers shared by cooperating task producers;
 * they do not grant access or make an action thread-safe by themselves.
 *
 * @param id globally unique, namespaced task identifier
 * @param kind executor affinity selected by the plan consumer
 * @param reads effects read while the action runs
 * @param writes effects mutated while the action runs
 * @param after explicit task ids that must complete first
 * @param deterministic whether identical inputs produce identical effects
 * @param cacheable whether a future runtime may persist the deterministic result
 * @param estimatedNanos scheduling estimate used only for critical-path reporting
 * @param action work to execute
 */
public record StartupTask(
        String id,
        Kind kind,
        Set<String> reads,
        Set<String> writes,
        Set<String> after,
        boolean deterministic,
        boolean cacheable,
        long estimatedNanos,
        Runnable action) {
    private static final int MAX_EFFECTS = 1024;

    public StartupTask {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid startup task id " + id);
        }
        if (kind == null || action == null || estimatedNanos < 0) {
            throw new IllegalArgumentException("startup task is incomplete " + id);
        }
        reads = checked(reads, "reads");
        writes = checked(writes, "writes");
        after = checked(after, "after");
        if (cacheable && !deterministic) {
            throw new IllegalArgumentException("cacheable startup task must be deterministic " + id);
        }
    }

    public static Builder builder(String id, Kind kind, Runnable action) {
        return new Builder(id, kind, action);
    }

    private static Set<String> checked(Set<String> values, String label) {
        Set<String> copy = values == null ? Set.of() : Set.copyOf(values);
        if (copy.size() > MAX_EFFECTS || copy.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("invalid startup task " + label);
        }
        return copy;
    }

    public enum Kind {
        CPU_PURE,
        BLOCKING_IO,
        CLASS_DEFINE,
        MAIN_THREAD,
        RENDER_THREAD,
        LOW_PRIORITY_WRITEBACK,
        LEGACY
    }

    public static final class Builder {
        private final String id;
        private final Kind kind;
        private final Runnable action;
        private Set<String> reads = Set.of();
        private Set<String> writes = Set.of();
        private Set<String> after = Set.of();
        private boolean deterministic;
        private boolean cacheable;
        private long estimatedNanos;

        private Builder(String id, Kind kind, Runnable action) {
            this.id = id;
            this.kind = kind;
            this.action = action;
        }

        public Builder reads(Set<String> values) {
            this.reads = values;
            return this;
        }

        public Builder writes(Set<String> values) {
            this.writes = values;
            return this;
        }

        public Builder after(Set<String> values) {
            this.after = values;
            return this;
        }

        public Builder deterministic(boolean value) {
            this.deterministic = value;
            return this;
        }

        public Builder cacheable(boolean value) {
            this.cacheable = value;
            return this;
        }

        public Builder estimatedNanos(long value) {
            this.estimatedNanos = value;
            return this;
        }

        public StartupTask build() {
            return new StartupTask(id, kind, reads, writes, after,
                    deterministic, cacheable, estimatedNanos, action);
        }
    }
}
