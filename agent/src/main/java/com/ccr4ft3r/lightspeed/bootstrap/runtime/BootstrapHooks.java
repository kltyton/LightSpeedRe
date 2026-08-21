package com.ccr4ft3r.lightspeed.bootstrap.runtime;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.discovery.TransformerServiceScanner;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.event.EventMethodCache;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.image.StartupResourceImage;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

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

    public static void registerResourceRoot(Path root, Path primary, BiPredicate<String, String> filter, Path[] paths) {
        ResourceMembershipIndex.register(root, primary, filter, paths);
    }

    public static String[] resourceEntries(Path path, String basePrefix, String requestedPath) {
        return ResourceMembershipIndex.entries(path, basePrefix, requestedPath);
    }

    public static int containsResource(Path path, String name) {
        return ResourceMembershipIndex.contains(path, name);
    }

    public static String[] resourceNamespaces(Path path, String directory) {
        return ResourceMembershipIndex.namespaces(path, directory);
    }

    public static Optional<Method> declaredEventMethod(Class<?> type, Method inherited) {
        return EventMethodCache.declaredMethod(type, inherited);
    }

    public static void startResourceImageLoad() {
        StartupResourceImage.startLoading();
    }

    public static byte[] resourceBytes(Path path, String name) {
        return ResourceMembershipIndex.resourceBytes(path, name);
    }

    public static void recordResourceBytes(Path path, String name, byte[] bytes) {
        ResourceMembershipIndex.recordResourceBytes(path, name, bytes);
    }

    public static void persistResourceImage() {
        StartupResourceImage.persist();
    }

    private static void printSummary() {
        System.err.println("[Lightspeed Agent] summary serviceCandidates=" + TransformerServiceScanner.candidates()
                + " serviceRejected=" + TransformerServiceScanner.rejected()
                + " resourceQueries=" + ResourceMembershipIndex.queries()
                + " resourceRejected=" + ResourceMembershipIndex.rejected()
                + " indexes=" + ResourceMembershipIndex.indexCount()
                + " indexedEntries=" + ResourceMembershipIndex.indexedEntries()
                + " qualificationChecks=" + ResourceMembershipIndex.qualificationChecks()
                + " eventMethodHits=" + EventMethodCache.hits()
                + " eventMethodMisses=" + EventMethodCache.misses()
                + " imageHits=" + StartupResourceImage.hits()
                + " imageMisses=" + StartupResourceImage.misses()
                + " imageRecordedBytes=" + StartupResourceImage.recordedBytes()
                + " failures=" + (TransformerServiceScanner.failures()
                + ResourceMembershipIndex.failures()
                + StartupResourceImage.failures()));
    }
}
