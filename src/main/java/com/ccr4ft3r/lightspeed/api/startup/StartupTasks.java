package com.ccr4ft3r.lightspeed.api.startup;

import com.ccr4ft3r.lightspeed.startup.tasks.StartupTaskPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide registry for code that explicitly adopts the startup task ABI.
 * Registering a task never relocates Forge constructors, events, registry work,
 * or other legacy lifecycle callbacks. Freezing validates and publishes one
 * plan; repeated freezes return that same plan instance.
 */
public final class StartupTasks {
    private static final int MAX_TASKS = 4096;
    private static final Object LOCK = new Object();
    private static final List<StartupTask> TASKS = new ArrayList<>();
    private static boolean frozen;
    private static StartupTaskPlan frozenPlan;

    private StartupTasks() {
    }

    public static void register(StartupTask task) {
        synchronized (LOCK) {
            if (frozen) {
                throw new IllegalStateException("startup task registration is frozen");
            }
            if (TASKS.size() >= MAX_TASKS) {
                throw new IllegalStateException("startup task limit exceeded");
            }
            if (TASKS.stream().anyMatch(existing -> existing.id().equals(task.id()))) {
                throw new IllegalArgumentException("duplicate startup task " + task.id());
            }
            TASKS.add(task);
        }
    }

    /**
     * Validates a deterministic execution plan, then closes registration.
     * Repeated calls are idempotent and return the originally published plan.
     * The lifecycle owner, rather than an arbitrary worker, is expected to call
     * this once all participating producers have registered.
     */
    public static StartupTaskPlan freeze() {
        synchronized (LOCK) {
            if (frozenPlan != null) {
                return frozenPlan;
            }
            StartupTaskPlan plan = StartupTaskPlan.create(List.copyOf(TASKS));
            frozenPlan = plan;
            frozen = true;
            return plan;
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            TASKS.clear();
            frozen = false;
            frozenPlan = null;
        }
    }
}
