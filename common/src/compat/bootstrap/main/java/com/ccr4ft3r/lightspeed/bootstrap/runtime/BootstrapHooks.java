package com.ccr4ft3r.lightspeed.bootstrap.runtime;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.discovery.TransformerServiceScanner;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.attribution.ClassDefinitionAttribution;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.event.EventMethodCache;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.image.StartupResourceImage;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.registry.ObjectHolderRouter;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.registry.KeyedRegisterRouting;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.scan.ScanMetadataCache;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.scan.ModScanExecutorFactory;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.transform.OpcodeNames;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.transform.MixinTargetIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.transform.ClassPassThrough;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.transform.AccessTransformerIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.loading.ModTransitions;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.loading.ModContainerPreparation;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.network.StartupHttpWindow;

import java.lang.module.ModuleReference;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.stream.Stream;

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

    public static void startStartupHttpWindow() { StartupHttpWindow.start(); }
    public static void finishStartupHttpWindow() { StartupHttpWindow.finish(); }
    public static Optional<Duration> limitHttpClientConnect(Optional<Duration> configured) {
        return StartupHttpWindow.limitHttpClientConnect(configured);
    }

    public static boolean mayProvideTransformerService(Path path) {
        return TransformerServiceScanner.mayProvide(path);
    }

    public static boolean mightContain(Path root, Path primary, String name) {
        return ResourceMembershipIndex.mightContain(root, primary, name);
    }

    public static void validateUnionInputOptions(OpenOption[] options) {
        if (options.length > 1 || options.length == 1 && options[0] != StandardOpenOption.READ) {
            throw new UnsupportedOperationException();
        }
    }

    public static InputStream openUnionInput(Path path) throws IOException {
        if (path == null) throw new FileNotFoundException();
        return Files.newInputStream(path);
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

    public static Set<String> resourcePackages(Path root) {
        return ResourceMembershipIndex.packages(root);
    }

    public static void objectHoldersChanged() {
        ObjectHolderRouter.invalidate();
    }

    public static void applyObjectHolders(Set<?> handlers, Predicate<?> filter) {
        ObjectHolderRouter.apply(handlers, filter);
    }

    public static void withRegistryFilter(Predicate<?> filter, Object key, Runnable action) {
        ObjectHolderRouter.withRegistryFilter(filter, key, action);
    }

    public static <T> Consumer<T> keyedRegisterConsumer(Object key, Consumer<T> action) {
        return KeyedRegisterRouting.keyedConsumer(key, action);
    }

    public static void withRegisterEvent(Object event, Object key, Runnable action) {
        KeyedRegisterRouting.withEvent(event, key, action);
    }

    public static void recordKeyedRegisterListener(Object target, Object listener) {
        KeyedRegisterRouting.record(target, listener);
    }

    public static void forgetKeyedRegisterListener(Object listener) {
        KeyedRegisterRouting.forget(listener);
    }

    public static boolean skipKeyedRegisterListener(Object event, Object listener) {
        return KeyedRegisterRouting.skip(event, listener);
    }

    public static boolean skipEmptyRegisterBus(Object event, Object[] listeners) {
        return KeyedRegisterRouting.skipBus(event, listeners);
    }

    public static Optional<Method> declaredEventMethod(Class<?> type, Method inherited) {
        return EventMethodCache.declaredMethod(type, inherited);
    }

    public static int eventBusCapacity(int required) {
        return required <= 1 ? required : Integer.highestOneBit(required - 1) << 1;
    }

    public static boolean canUseDirectEventWrapper(Method callback) {
        return isPubliclyAccessible(callback.getDeclaringClass())
                && Modifier.isPublic(callback.getModifiers())
                && isPubliclyAccessible(callback.getParameterTypes()[0]);
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

    public static boolean replayScanMetadata(Object modFile, Object scanData) {
        return ScanMetadataCache.replay(modFile, scanData);
    }

    public static void recordScanMetadata(Object modFile, Object scanData) {
        ScanMetadataCache.record(modFile, scanData);
    }

    public static ExecutorService createModScanExecutor(ThreadFactory threadFactory) {
        return ModScanExecutorFactory.create(threadFactory);
    }

    public static String opcodeName(Class<?> constants, int opcode) {
        return OpcodeNames.get(constants, opcode);
    }

    public static Object createMixinTargetIndex() {
        return MixinTargetIndex.create();
    }

    public static List<?> mixinConfigsFor(List<?> configurations, Object index, String className) {
        return MixinTargetIndex.select(configurations, index, className);
    }

    public static void invalidateMixinTargetIndex() {
        MixinTargetIndex.invalidate();
    }

    public static void publishMixinClassIndex(Object index, Object environment, Object coprocessors, Object hotSwapper,
            List<?> generators) {
        MixinTargetIndex.publish(index, environment, coprocessors, hotSwapper, generators);
    }

    public static boolean passThroughClass(byte[] bytes, String name, String reason, Object asmType,
            Map<?, ?> phases, boolean hasTransformers, Object audit, ClassLoader loader) {
        return ClassPassThrough.transform(bytes, name, reason, asmType, phases, hasTransformers, audit, loader);
    }

    public static EnumSet<?> mixinPhases(Object plugin, Object type, boolean empty, String reason) {
        return ClassPassThrough.phases(plugin, type, empty, reason);
    }

    public static boolean skipMixinPlugin(Object plugin, Object phase, Object asmType, String name, String reason) {
        return ClassPassThrough.skipMixinPlugin(plugin, phase, asmType, name, reason);
    }

    public static Map<Object, List<Object>> indexAccessTransformers(Map<?, ?> rules, Function<Object, Object> targetType) {
        return AccessTransformerIndex.build(rules, targetType);
    }

    public static Stream<Object> prepareModContainers(Stream<?> files, Function<Object, Object> factory,
            Executor executor, Runnable ticker) {
        return ModContainerPreparation.build(files, factory, executor, ticker);
    }

    public static CompletableFuture<Void> runModTransition(Runnable action, Executor executor,
            BiConsumer<? super Void, ? super Throwable> completion, String modId, String phase) {
        return ModTransitions.run(action, executor, completion, modId, phase);
    }

    public static void persistResourceImage() {
        StartupResourceImage.persist();
    }

    private static void printSummary() {
        ClassDefinitionAttribution.Snapshot attribution = ClassDefinitionAttribution.stopAndSnapshot();
        StartupResourceImage.persistOnExit();
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
                + " mixinIndexedQueries=" + MixinTargetIndex.queries()
                + " mixinConfigsSkipped=" + MixinTargetIndex.skipped()
                + " unchangedClasses=" + ClassPassThrough.bypassed()
                + " mixinPhaseFastQueries=" + ClassPassThrough.phaseQueries()
                + " mixinPluginSkipped=" + ClassPassThrough.mixinPluginSkipped()
                + " atIndexBuilds=" + AccessTransformerIndex.builds()
                + " atIndexedTypes=" + AccessTransformerIndex.types()
                + " preparedModFiles=" + ModContainerPreparation.preparedFiles()
                + " modPreparationWorkers=" + ModContainerPreparation.workerCount()
                + " startupHttpWindowStarted=" + StartupHttpWindow.started()
                + " startupHttpWindowRestored=" + StartupHttpWindow.restored()
                + " startupHttpClientConnectBounds=" + StartupHttpWindow.httpClientBounds()
                + " objectHolderRouted=" + ObjectHolderRouter.routedCalls()
                + " objectHolderFallback=" + ObjectHolderRouter.fallbackCalls()
                + " objectHolderSkipped=" + ObjectHolderRouter.skippedHandlers()
                + " keyedRegisterSkipped=" + KeyedRegisterRouting.skipped()
                + " emptyRegisterBuses=" + KeyedRegisterRouting.emptyBuses()
                + " imageHits=" + StartupResourceImage.hits()
                + " imageMisses=" + StartupResourceImage.misses()
                + " imageRecordedBytes=" + StartupResourceImage.recordedBytes()
                + " classHits=" + StartupResourceImage.classHits()
                + " classMisses=" + StartupResourceImage.classMisses()
                + " classRecordedBytes=" + StartupResourceImage.classRecordedBytes()
                + " scanHits=" + ScanMetadataCache.hits()
                + " scanMisses=" + ScanMetadataCache.misses()
                + " scanRecorded=" + ScanMetadataCache.recorded()
                + " scanIncomplete=" + ScanMetadataCache.incomplete()
                + " attributedClasses=" + attribution.classes()
                + " attributionGroups=" + attribution.groups()
                + " attributionOverflow=" + attribution.overflow()
                + " eventBusWrapperLike=" + attribution.eventBusWrapperLike()
                + " failures=" + (TransformerServiceScanner.failures()
                + MixinTargetIndex.failures()
                + ResourceMembershipIndex.failures()
                + StartupResourceImage.failures()
                + ObjectHolderRouter.failures()
                + ScanMetadataCache.failures()));
        ClassDefinitionAttribution.printSummaryGroups(attribution);
    }

    private static boolean isPubliclyAccessible(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) {
            return false;
        }
        Module module = type.getModule();
        return !module.isNamed() || module.isExported(type.getPackageName());
    }

}
