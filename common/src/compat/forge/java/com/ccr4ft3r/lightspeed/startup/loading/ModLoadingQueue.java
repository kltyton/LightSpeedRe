package com.ccr4ft3r.lightspeed.startup.loading;

import net.minecraftforge.fml.ModWorkManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Replaces ModLoader's polling spin loop and ModernFix's 25ms+25ms queue sleep with an unlost signal-driven wait loop.
 */
public final class ModLoadingQueue {
    private static final long PERIODIC_PARK_NANOS = 16_000_000L;
    private static final AtomicLong QUEUE_SIGNAL = new AtomicLong(0L);
    private static volatile Thread waitingThread;

    private ModLoadingQueue() {
    }

    public static void signalWork() {
        QUEUE_SIGNAL.incrementAndGet();
        Thread target = waitingThread;
        if (target != null) {
            LockSupport.unpark(target);
        }
    }

    public static void waitForTransition(
            CompletableFuture<?> future,
            ModWorkManager.DrivenExecutor syncExecutor,
            Runnable periodicTask
    ) {
        if (future.isDone()) {
            return;
        }
        Thread current = Thread.currentThread();
        Thread previous = waitingThread;
        waitingThread = current;
        future.whenComplete((ignored, error) -> signalWork());
        try {
            while (!future.isDone()) {
                long stamp = QUEUE_SIGNAL.get();
                syncExecutor.drive(periodicTask);
                if (future.isDone()) {
                    break;
                }
                if (QUEUE_SIGNAL.get() != stamp) {
                    continue;
                }
                LockSupport.parkNanos(PERIODIC_PARK_NANOS);
            }
        } finally {
            waitingThread = previous;
            if (previous != null) {
                LockSupport.unpark(previous);
            }
        }
    }
}
