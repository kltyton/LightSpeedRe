package com.ccr4ft3r.lightspeed.bootstrap.runtime.scan;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class ModScanExecutorFactory {
    private ModScanExecutorFactory() {
    }

    public static ExecutorService create(ThreadFactory threadFactory) {
        int workers = workerCount(
                Integer.getInteger("lightspeed.scanWorkers", 0),
                Integer.getInteger("lightspeed.workers", 0),
                Runtime.getRuntime().availableProcessors());
        return workers == 1
                ? Executors.newSingleThreadExecutor(threadFactory)
                : Executors.newFixedThreadPool(workers, threadFactory);
    }

    public static int workerCount(int configured, int startupWorkers, int processors) {
        if (configured > 0) {
            return Math.max(1, Math.min(8, configured));
        }
        int available = startupWorkers > 0 ? startupWorkers : Math.max(1, processors - 2);
        return Math.max(1, Math.min(4, available / 2));
    }
}
