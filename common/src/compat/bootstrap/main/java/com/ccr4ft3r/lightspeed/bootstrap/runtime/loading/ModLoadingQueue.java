package com.ccr4ft3r.lightspeed.bootstrap.runtime.loading;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/** Separates nonblocking loading-queue drains from waits for an unfinished transition. */
public final class ModLoadingQueue {
    private static final ThreadLocal<WaitScope> CURRENT = new ThreadLocal<>();
    private static final LongAdder EMPTY_DRAINS = new LongAdder();
    private static final LongAdder WAKEUPS = new LongAdder();
    private static volatile WaitScope active;
    private static volatile String observedQueue = "<unobserved>";

    private ModLoadingQueue() { }

    public static void waitForTransition(CompletableFuture<?> transition, Runnable work) {
        WaitScope previous = CURRENT.get();
        WaitScope scope = new WaitScope(transition, Thread.currentThread());
        CURRENT.set(scope);
        active = scope;
        try {
            transition.whenComplete((ignored, failure) -> scope.wake());
            work.run();
        } finally {
            scope.active = false;
            active = previous;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static Object pollFirst(ConcurrentLinkedDeque<?> queue) {
        observedQueue = queue.getClass().getName();
        WaitScope scope = CURRENT.get();
        if (scope == null || scope.transition.isDone()) {
            return drain(queue);
        }

        scope.queue = queue;
        scope.parking = true;
        try {
            // Completion can race with the first check before the native queue parks.
            return scope.transition.isDone() ? drain(queue) : queue.pollFirst();
        } finally {
            scope.parking = false;
        }
    }

    private static Object drain(ConcurrentLinkedDeque<?> queue) {
        // Forge's loading thread is the sole consumer; workers only enqueue tasks.
        if (queue.peekFirst() == null) {
            EMPTY_DRAINS.increment();
            return null;
        }
        return queue.pollFirst();
    }

    public static void taskEnqueued(ConcurrentLinkedDeque<?> queue) {
        WaitScope scope = active;
        if (scope != null && scope.queue == queue) scope.wake();
    }

    public static long emptyDrains() { return EMPTY_DRAINS.sum(); }
    public static long wakeups() { return WAKEUPS.sum(); }
    public static String queueType() { return observedQueue; }

    private static final class WaitScope {
        private final CompletableFuture<?> transition;
        private final Thread thread;
        private volatile ConcurrentLinkedDeque<?> queue;
        private volatile boolean parking;
        private volatile boolean active = true;

        private WaitScope(CompletableFuture<?> transition, Thread thread) {
            this.transition = transition;
            this.thread = thread;
        }

        private void wake() {
            if (active && parking) {
                WAKEUPS.increment();
                LockSupport.unpark(thread);
            }
        }
    }
}
