package com.ccr4ft3r.lightspeed.bootstrap.runtime.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModScanExecutorFactoryCheck {
    private ModScanExecutorFactoryCheck() {
    }

    public static void main(String[] args) throws Exception {
        require(ModScanExecutorFactory.workerCount(0, 6, 16) == 3,
                "managed startup budget did not bound scan concurrency");
        require(ModScanExecutorFactory.workerCount(0, 0, 16) == 4,
                "default scan concurrency did not retain the four-worker cap");
        require(ModScanExecutorFactory.workerCount(1, 6, 16) == 1,
                "explicit serial scan override was ignored");
        require(ModScanExecutorFactory.workerCount(99, 6, 16) == 8,
                "explicit scan override was not bounded");

        String previous = System.getProperty("lightspeed.scanWorkers");
        System.setProperty("lightspeed.scanWorkers", "3");
        ExecutorService executor = ModScanExecutorFactory.create(runnable -> {
            Thread thread = new Thread(runnable, "scan-check");
            thread.setDaemon(true);
            return thread;
        });
        try {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(3);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < 9; index++) {
                final int result = index;
                futures.add(executor.submit(() -> {
                    int running = active.incrementAndGet();
                    maximum.accumulateAndGet(running, Math::max);
                    entered.countDown();
                    release.await();
                    active.decrementAndGet();
                    return result;
                }));
            }
            require(entered.await(5, TimeUnit.SECONDS), "three independent Mod scans did not run concurrently");
            require(maximum.get() == 3, "Mod scan concurrency exceeded or missed its configured bound");
            release.countDown();
            for (int index = 0; index < futures.size(); index++) {
                require(futures.get(index).get(5, TimeUnit.SECONDS) == index,
                        "parallel Mod scan result lost its input-indexed identity");
            }

            IllegalStateException failure = new IllegalStateException("scan failure");
            Future<?> failed = executor.submit(() -> {
                throw failure;
            });
            try {
                failed.get(5, TimeUnit.SECONDS);
                throw new AssertionError("Mod scan exception was swallowed");
            } catch (ExecutionException exception) {
                require(exception.getCause() == failure, "Mod scan exception identity changed");
            }
        } finally {
            executor.shutdownNow();
            if (previous == null) {
                System.clearProperty("lightspeed.scanWorkers");
            } else {
                System.setProperty("lightspeed.scanWorkers", previous);
            }
        }
        System.out.println("MOD_SCAN_EXECUTOR_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
