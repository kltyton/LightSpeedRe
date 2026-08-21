package com.ccr4ft3r.lightspeed.bootstrap.runtime;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.discovery.TransformerServiceScanner;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.event.EventMethodCache;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.image.StartupResourceImage;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.scan.ScanMetadataCache;

import java.lang.module.ModuleReference;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    public static void installInstrumentation(Instrumentation instrumentation) {
        RuntimeModuleAccess.install(instrumentation);
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

    public static int bindResourceIndex(Path path) {
        return ResourceMembershipIndex.bind(path);
    }

    public static List<String> resourceEntries(int handle, String basePrefix, String requestedPath) {
        return ResourceMembershipIndex.entries(handle, basePrefix, requestedPath);
    }

    public static int containsResource(int handle, String name) {
        return ResourceMembershipIndex.contains(handle, name);
    }

    public static Set<String> resourceNamespaces(int handle, String directory) {
        return ResourceMembershipIndex.namespaces(handle, directory);
    }

    public static Optional<Method> declaredEventMethod(Class<?> type, Method inherited) {
        return EventMethodCache.declaredMethod(type, inherited);
    }

    public static void startResourceImageLoad() {
        StartupResourceImage.startLoading();
    }

    public static byte[] resourceBytes(int handle, String name) {
        return ResourceMembershipIndex.resourceBytes(handle, name);
    }

    public static void recordResourceBytes(int handle, String name, byte[] bytes) {
        ResourceMembershipIndex.recordResourceBytes(handle, name, bytes);
    }

    public static byte[] rawClassBytes(ModuleReference reference, String name) {
        return StartupResourceImage.rawClass(reference, name);
    }

    public static void recordRawClassBytes(ModuleReference reference, String name, byte[] bytes) {
        StartupResourceImage.recordRawClass(reference, name, bytes);
    }

    public static boolean replayScanMetadata(Path path, Object scanData) {
        return ScanMetadataCache.replay(path, scanData);
    }

    public static void recordScanMetadata(Path path, Object scanData) {
        ScanMetadataCache.record(path, scanData);
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
                + " views=" + ResourceMembershipIndex.viewCount()
                + " indexedEntries=" + ResourceMembershipIndex.indexedEntries()
                + " qualificationChecks=" + ResourceMembershipIndex.qualificationChecks()
                + " eventMethodHits=" + EventMethodCache.hits()
                + " eventMethodMisses=" + EventMethodCache.misses()
                + " imageHits=" + StartupResourceImage.hits()
                + " imageMisses=" + StartupResourceImage.misses()
                + " imageRecordedBytes=" + StartupResourceImage.recordedBytes()
                + " classHits=" + StartupResourceImage.classHits()
                + " classMisses=" + StartupResourceImage.classMisses()
                + " classRecordedBytes=" + StartupResourceImage.classRecordedBytes()
                + " scanHits=" + ScanMetadataCache.hits()
                + " scanMisses=" + ScanMetadataCache.misses()
                + " scanRecorded=" + ScanMetadataCache.recorded()
                + " failures=" + (TransformerServiceScanner.failures()
                + ResourceMembershipIndex.failures()
                + StartupResourceImage.failures()
                + ScanMetadataCache.failures()));
    }
}
