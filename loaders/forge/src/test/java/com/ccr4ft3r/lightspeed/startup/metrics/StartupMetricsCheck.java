package com.ccr4ft3r.lightspeed.startup.metrics;

import java.util.concurrent.CompletableFuture;

public final class StartupMetricsCheck {
    private StartupMetricsCheck() {
    }

    public static void main(String[] args) {
        require(StartupMetrics.processStartEpochMillis() > 0, "process start epoch was not available");
        require(StartupMetrics.elapsedMillis() >= 0, "startup elapsed time was negative");
        String marker = "verification-" + ProcessHandle.current().pid();
        require(StartupMetrics.mark(marker), "first startup milestone was not recorded");
        require(!StartupMetrics.mark(marker), "duplicate startup milestone was recorded twice");
        CompletableFuture<Void> firstReload = new CompletableFuture<>();
        StartupMetrics.observeInitialReload(firstReload);
        require(!StartupMetrics.isInitialReloadComplete(), "unfinished reload was treated as complete");
        firstReload.complete(null);
        require(StartupMetrics.isInitialReloadComplete(), "successful reload was not observed");
        CompletableFuture<Void> followingReload = new CompletableFuture<>();
        StartupMetrics.observeInitialReload(followingReload);
        require(!StartupMetrics.isInitialReloadComplete(), "the prior reload hid a pending startup reload");
        followingReload.completeExceptionally(new IllegalStateException("reload failed"));
        require(!StartupMetrics.isInitialReloadComplete(), "failed reload was treated as successful");
        StartupMetrics.observeInitialReload(CompletableFuture.completedFuture(null));
        require(StartupMetrics.isInitialReloadComplete(), "successful recovery did not restore readiness");
        require(StartupMetrics.mark("title-screen-operable"), "title completion was not recorded");
        long completed = StartupMetrics.completedStartupMillis();
        require(completed >= 0 && completed <= StartupMetrics.elapsedMillis(), "frozen startup duration is invalid");
        StartupMetrics.observeInitialReload(new CompletableFuture<>());
        require(!StartupMetrics.mark("title-screen-operable") && StartupMetrics.completedStartupMillis() == completed,
                "a subsequent menu or reload changed the frozen duration");
        System.out.println("STARTUP_METRICS_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
