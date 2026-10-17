package com.ccr4ft3r.lightspeed.bootstrap.runtime.attribution;

import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.List;

public final class ClassDefinitionAttributionCheck {
    private ClassDefinitionAttributionCheck() {
    }

    public static void main(String[] args) throws Exception {
        boundedGroupsPreserveTotalsAndWrapperSignal();
        repeatedGroupFastPathStaysLowOverhead();
        snapshotStopsAndReconcilesCounters();
        System.out.println("CLASS_DEFINITION_ATTRIBUTION_OK");
    }

    private static void boundedGroupsPreserveTotalsAndWrapperSignal() throws Exception {
        ClassLoader loader = new FixtureLoader();
        ClassDefinitionAttribution.record(Object.class.getModule(), null, "java/lang/BootstrapOnly",
                protectionDomain("file:/bootstrap.jar"));
        for (int index = 0; index < 10; index++) {
            String className = index == 0 ? "sample/mod/__Owner_handle_Event" : "sample/mod/Type" + index;
            ClassDefinitionAttribution.record(Object.class.getModule(), loader, className,
                    protectionDomain("file:/fixture-" + index + ".jar"));
        }
        require(ClassDefinitionAttribution.total() == 10, "class-definition total was not preserved");
        require(ClassDefinitionAttribution.groupCount() == 4, "attribution group bound was not enforced");
        require(ClassDefinitionAttribution.overflow() == 6, "overflow definitions were not counted");
        require(ClassDefinitionAttribution.eventBusWrapperLike() == 1,
                "EventBus wrapper-like definition was not counted");
        List<ClassDefinitionAttribution.Summary> summaries = ClassDefinitionAttribution.summaries();
        require(summaries.stream().anyMatch(summary -> summary.module().equals("java.base")
                        && summary.loader().contains("ClassDefinitionAttributionCheck")
                        && summary.source().equals("file:/fixture-0.jar")
                        && summary.eventBusWrapperLike() == 1),
                "module/source/loader wrapper attribution was not retained");
    }

    private static void repeatedGroupFastPathStaysLowOverhead() throws Exception {
        ClassLoader loader = new FixtureLoader();
        ProtectionDomain domain = protectionDomain("file:/fixture-0.jar");
        for (int index = 0; index < 20_000; index++) {
            ClassDefinitionAttribution.record(Object.class.getModule(), loader, "sample/FastPath", domain);
        }
        int iterations = 500_000;
        long started = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            ClassDefinitionAttribution.record(Object.class.getModule(), loader, "sample/FastPath", domain);
        }
        long elapsed = System.nanoTime() - started;
        long nanosPerDefinition = elapsed / iterations;
        require(nanosPerDefinition < 10_000,
                "class-definition attribution exceeded 10 microseconds per callback: " + nanosPerDefinition);
        System.out.println("CLASS_ATTRIBUTION_NS_PER_DEFINITION=" + nanosPerDefinition);
    }

    private static ProtectionDomain protectionDomain(String location) throws Exception {
        return new ProtectionDomain(new CodeSource(new URL(location), (Certificate[]) null), null);
    }

    private static void snapshotStopsAndReconcilesCounters() throws Exception {
        ClassDefinitionAttribution.Snapshot snapshot = ClassDefinitionAttribution.stopAndSnapshot();
        long grouped = snapshot.summaries().stream()
                .mapToLong(ClassDefinitionAttribution.Summary::classes)
                .sum();
        require(snapshot.classes() == grouped + snapshot.overflow(),
                "snapshot class total did not reconcile with groups and overflow");
        long before = snapshot.classes();
        ClassDefinitionAttribution.record(null, new ClassLoader(null) {
                }, "sample/AfterStop",
                protectionDomain("file:/after-stop.jar"));
        require(ClassDefinitionAttribution.stopAndSnapshot().classes() == before,
                "attribution continued after the shutdown snapshot");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader() {
            super(null);
        }
    }
}
