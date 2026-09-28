package com.ccr4ft3r.lightspeed.startup.tasks;

import com.ccr4ft3r.lightspeed.api.startup.StartupTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dependency plan for explicitly declared startup tasks. Conflicting effects
 * preserve registration order; unrelated tasks may run concurrently on
 * caller-owned executors. Each plan instance can be executed exactly once.
 */
public final class StartupTaskPlan {
    private static final int MAX_TASKS = 4096;

    private final List<Node> ordered;
    private final AtomicBoolean executed = new AtomicBoolean();

    private StartupTaskPlan(List<Node> ordered) {
        this.ordered = ordered;
    }

    public static StartupTaskPlan create(List<StartupTask> tasks) {
        if (tasks == null) {
            throw new IllegalArgumentException("startup task list must not be null");
        }
        if (tasks.size() > MAX_TASKS) {
            throw new IllegalArgumentException("startup task limit exceeded");
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (int index = 0; index < tasks.size(); index++) {
            StartupTask task = tasks.get(index);
            if (task == null) {
                throw new IllegalArgumentException("startup task entry must not be null");
            }
            if (nodes.putIfAbsent(task.id(), new Node(task, index)) != null) {
                throw new IllegalArgumentException("duplicate startup task " + task.id());
            }
        }
        for (Node node : nodes.values()) {
            for (String dependency : node.task.after()) {
                Node required = nodes.get(dependency);
                if (required == null) {
                    throw new IllegalArgumentException("unknown startup dependency " + dependency);
                }
                node.dependencies.add(required);
            }
        }
        Node previousLegacy = null;
        List<Node> registered = new ArrayList<>(nodes.values());
        Map<String, Node> lastWriters = new HashMap<>();
        Map<String, Set<Node>> readersSinceLastWrite = new HashMap<>();
        for (Node current : registered) {
            if (current.task.kind() == StartupTask.Kind.LEGACY) {
                if (previousLegacy != null) {
                    current.dependencies.add(previousLegacy);
                }
                previousLegacy = current;
            }
            for (String effect : current.task.reads()) {
                Node lastWriter = lastWriters.get(effect);
                if (lastWriter != null) {
                    current.dependencies.add(lastWriter);
                }
            }
            for (String effect : current.task.writes()) {
                Node lastWriter = lastWriters.get(effect);
                if (lastWriter != null) {
                    current.dependencies.add(lastWriter);
                }
                Set<Node> readers = readersSinceLastWrite.get(effect);
                if (readers != null) {
                    current.dependencies.addAll(readers);
                }
            }
            for (String effect : current.task.writes()) {
                lastWriters.put(effect, current);
                readersSinceLastWrite.remove(effect);
            }
            for (String effect : current.task.reads()) {
                if (!current.task.writes().contains(effect)) {
                    readersSinceLastWrite.computeIfAbsent(effect, ignored -> new LinkedHashSet<>()).add(current);
                }
            }
        }
        nodes.values().forEach(node -> node.dependencies.forEach(dependency -> dependency.dependents.add(node)));
        List<Node> ordered = topological(nodes.values());
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Node node = ordered.get(index);
            long downstream = node.dependents.stream().mapToLong(value -> value.criticalNanos).max().orElse(0L);
            node.criticalNanos = saturatingAdd(node.task.estimatedNanos(), downstream);
        }
        return new StartupTaskPlan(List.copyOf(ordered));
    }

    public List<String> order() {
        return ordered.stream().map(node -> node.task.id()).toList();
    }

    /**
     * Returns the estimated critical path in nanoseconds. Estimates that exceed
     * the {@code long} range saturate at {@link Long#MAX_VALUE}.
     */
    public long criticalPathNanos() {
        return ordered.stream().mapToLong(node -> node.criticalNanos).max().orElse(0L);
    }

    /**
     * Executes every task once. The plan is consumed atomically by the first
     * valid call; later calls fail with {@link IllegalStateException}. A failed
     * task prevents its transitive dependents from running while independent
     * branches continue; the returned future completes only after every branch
     * has reached a terminal state.
     */
    public CompletableFuture<Void> execute(Executors executors) {
        if (executors == null) {
            throw new IllegalArgumentException("startup task executors are incomplete");
        }
        if (!executed.compareAndSet(false, true)) {
            throw new IllegalStateException("startup task plan has already been executed");
        }
        Map<Node, CompletableFuture<Void>> futures = new HashMap<>();
        for (Node node : ordered) {
            CompletableFuture<?>[] dependencies = node.dependencies.stream()
                    .map(futures::get).toArray(CompletableFuture[]::new);
            CompletableFuture<Void> ready = CompletableFuture.allOf(dependencies);
            futures.put(node, ready.thenRunAsync(node.task.action(), executors.forKind(node.task.kind())));
        }
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new));
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static List<Node> topological(java.util.Collection<Node> nodes) {
        Map<Node, Integer> indegree = new HashMap<>();
        ArrayDeque<Node> ready = new ArrayDeque<>();
        nodes.forEach(node -> {
            indegree.put(node, node.dependencies.size());
            if (node.dependencies.isEmpty()) {
                ready.add(node);
            }
        });
        List<Node> ordered = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            List<Node> level = new ArrayList<>();
            while (!ready.isEmpty()) {
                level.add(ready.removeFirst());
            }
            level.sort(Comparator.comparingInt(node -> node.registrationOrder));
            for (Node node : level) {
                ordered.add(node);
                for (Node dependent : node.dependents) {
                    int remaining = indegree.computeIfPresent(dependent, (ignored, value) -> value - 1);
                    if (remaining == 0) {
                        ready.addLast(dependent);
                    }
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            Set<String> cyclic = new HashSet<>();
            indegree.forEach((node, value) -> {
                if (value > 0) cyclic.add(node.task.id());
            });
            throw new IllegalArgumentException("startup task dependency cycle " + cyclic);
        }
        return ordered;
    }

    public record Executors(Executor cpu, Executor blockingIo, Executor classDefine,
                            Executor mainThread, Executor renderThread, Executor writeback,
                            Executor legacy) {
        public Executors {
            if (cpu == null || blockingIo == null || classDefine == null || mainThread == null
                    || renderThread == null || writeback == null || legacy == null) {
                throw new IllegalArgumentException("startup task executors are incomplete");
            }
        }

        private Executor forKind(StartupTask.Kind kind) {
            return switch (kind) {
                case CPU_PURE -> cpu;
                case BLOCKING_IO -> blockingIo;
                case CLASS_DEFINE -> classDefine;
                case MAIN_THREAD -> mainThread;
                case RENDER_THREAD -> renderThread;
                case LOW_PRIORITY_WRITEBACK -> writeback;
                case LEGACY -> legacy;
            };
        }
    }

    private static final class Node {
        private final StartupTask task;
        private final int registrationOrder;
        private final Set<Node> dependencies = new LinkedHashSet<>();
        private final Set<Node> dependents = new LinkedHashSet<>();
        private long criticalNanos;

        private Node(StartupTask task, int registrationOrder) {
            this.task = task;
            this.registrationOrder = registrationOrder;
        }
    }
}
