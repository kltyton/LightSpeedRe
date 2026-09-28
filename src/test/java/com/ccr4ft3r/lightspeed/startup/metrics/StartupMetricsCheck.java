package com.ccr4ft3r.lightspeed.startup.metrics;

public final class StartupMetricsCheck {
    private StartupMetricsCheck() {
    }

    public static void main(String[] args) {
        require(StartupMetrics.processStartEpochMillis() > 0, "process start epoch was not available");
        require(StartupMetrics.elapsedMillis() >= 0, "startup elapsed time was negative");
        String marker = "verification-" + ProcessHandle.current().pid();
        require(StartupMetrics.mark(marker), "first startup milestone was not recorded");
        require(!StartupMetrics.mark(marker), "duplicate startup milestone was recorded twice");
        System.out.println("STARTUP_METRICS_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
