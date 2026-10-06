package com.ccr4ft3r.lightspeed.startup.metrics;

import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import com.mojang.logging.LogUtils;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class StartupMetrics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> RECORDED = ConcurrentHashMap.newKeySet();
    private static volatile boolean initialReloadComplete;

    private StartupMetrics() {
    }

    public static void observeInitialReload(CompletableFuture<?> completion) {
        if (!hasRecorded("title-screen-operable")) {
            completion.thenRun(() -> {
                mark("initial-resource-reload-complete");
                initialReloadComplete = true;
            });
        }
    }

    public static boolean hasRecorded(String milestone) {
        return RECORDED.contains(milestone);
    }

    public static boolean isInitialReloadComplete() {
        return initialReloadComplete;
    }

    public static boolean mark(String milestone) {
        if (!RECORDED.add(milestone)) {
            return false;
        }
        long elapsedMillis = elapsedMillis();
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
