package com.ccr4ft3r.lightspeed.startup.metrics;

import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import com.mojang.logging.LogUtils;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Per-JVM milestones; the completed title time is shared by the log and every menu display. */
public final class StartupMetrics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Long> RECORDED = new ConcurrentHashMap<>();
    private static volatile CompletableFuture<?> initialReload;

    private StartupMetrics() {
    }

    public static void observeInitialReload(CompletableFuture<?> completion) {
        if (!hasRecorded("title-screen-operable")) {
            initialReload = completion;
            completion.thenRun(() -> mark("initial-resource-reload-complete"));
        }
    }

    public static boolean hasRecorded(String milestone) {
        return RECORDED.containsKey(milestone);
    }

    /** JVM uptime at the first fully loaded, operable title-screen tick, or -1 while loading. */
    public static long completedStartupMillis() {
        return RECORDED.getOrDefault("title-screen-operable", -1L);
    }

    public static boolean isInitialReloadComplete() {
        CompletableFuture<?> completion = initialReload;
        return completion != null && completion.isDone() && !completion.isCompletedExceptionally();
    }

    public static boolean mark(String milestone) {
        if (hasRecorded(milestone)) {
            return false;
        }
        long elapsedMillis = elapsedMillis();
        if (RECORDED.putIfAbsent(milestone, elapsedMillis) != null) {
            return false;
        }
        if ("title-screen-operable".equals(milestone)) {
            initialReload = CompletableFuture.completedFuture(null);
        }
        LOGGER.info("Lightspeed startup milestone: name={} elapsedMs={} processStartEpochMs={}",
                milestone, elapsedMillis, processStartEpochMillis());
        if ("title-screen-operable".equals(milestone) || "server-started".equals(milestone))
            BootstrapAgentBridge.finishStartupHttpWindow();
        MilestoneEvent event = new MilestoneEvent();
        if (event.isEnabled()) {
            event.milestone = milestone;
            event.elapsedMillis = elapsedMillis;
            event.processStartEpochMillis = processStartEpochMillis();
            event.commit();
        }
        return true;
    }

    public static long elapsedMillis() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    public static long processStartEpochMillis() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    @Name("com.ccr4ft3r.lightspeed.StartupMilestone")
    @Label("Lightspeed Startup Milestone")
    static final class MilestoneEvent extends Event {
        @Label("Milestone")
        String milestone;

        @Label("Elapsed Milliseconds")
        long elapsedMillis;

        @Label("Process Start Epoch Milliseconds")
        long processStartEpochMillis;
    }
}
