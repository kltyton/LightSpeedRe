package com.ccr4ft3r.lightspeed.bootstrap.runtime;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.discovery.TransformerServiceScanner;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BootstrapHooks {
    private static final AtomicBoolean SUMMARY_HOOK_INSTALLED = new AtomicBoolean();

    private BootstrapHooks() {
    }

    public static void installSummaryHook() {
        if (SUMMARY_HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(BootstrapHooks::printSummary, "Lightspeed-Agent-Summary"));
        }
    }

    public static boolean mayProvideTransformerService(Path path) {
        return TransformerServiceScanner.mayProvide(path);
    }

    public static boolean mightContain(Path root, Path primary, String name) {
        return ResourceMembershipIndex.mightContain(root, primary, name);
    }

    private static void printSummary() {
        System.err.println("[Lightspeed Agent] summary serviceCandidates=" + TransformerServiceScanner.candidates()
                + " serviceRejected=" + TransformerServiceScanner.rejected()
                + " resourceQueries=" + ResourceMembershipIndex.queries()
                + " resourceRejected=" + ResourceMembershipIndex.rejected()
                + " indexes=" + ResourceMembershipIndex.indexCount()
                + " indexedEntries=" + ResourceMembershipIndex.indexedEntries()
                + " failures=" + (TransformerServiceScanner.failures() + ResourceMembershipIndex.failures()));
    }
}
