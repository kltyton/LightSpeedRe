package com.ccr4ft3r.lightspeed.bootstrap;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.event.EventMethodCache;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.image.StartupResourceImage;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.scan.ScanMetadataCache;
import com.ccr4ft3r.lightspeed.bootstrap.transform.LauncherTransformer;
import net.minecraftforge.fml.loading.moddiscovery.ModAnnotation;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class AgentRegressionCheck {
    private static final String HOOK_OWNER = "com/ccr4ft3r/lightspeed/bootstrap/runtime/BootstrapHooks";

    private AgentRegressionCheck() {
    }

    public static void main(String[] args) throws Exception {
        transformsSupportedLauncherClasses();
        rejectsUnknownClassFingerprint();
        rejectsUnknownNestedLoaderFingerprint();
        unknownFingerprintDoesNotGrantModuleReads();
        supportedFingerprintGrantsModuleRead();
        jdkOnlyPatchDoesNotGrantModuleRead();
        servicePrefilterIsFailOpen();
        resourceIndexHasNoFalseNegatives();
        resourceImagePersistsRecordedBytes();
        scanMetadataRoundTripsWithoutClassIo();
        eventMethodCachePreservesReflectionSemantics();
        ownerGuardRejectsMismatchedAgent();
        packagedAgentIsIsolated();
    }

    private static void transformsSupportedLauncherClasses() throws Exception {
        List<String> messages = new ArrayList<>();
        LauncherTransformer transformer = new LauncherTransformer(messages::add);
        for (Target target : targets()) {
            byte[] original = readEntry(target.jar(), target.entry());
            require(sha256(original).equals(target.sha256()), "unexpected target bytes for " + target.className());
            byte[] transformed = transformer.transform(null, null, target.className(), null, null, original);
            require(transformed != null, "supported target was not transformed: " + target.className());
            require(!MessageDigest.isEqual(original, transformed), "transform returned unchanged bytes: " + target.className());
            for (String hookName : target.hookNames()) {
                require(hasHookCall(transformed, hookName), "transformed class lacks hook " + hookName);
            }
            if (target.className().equals("cpw/mods/cl/ModuleClassLoader")) {
                require(hasInvocation(transformed, Opcodes.INVOKESTATIC,
                                "java/lang/ClassLoader", "getSystemClassLoader"),
                        "ModuleClassLoader does not bridge Agent hooks through the system loader");
            }
            if (target.className().equals("net/minecraftforge/eventbus/ModLauncherFactory")) {
                require(hasInvocation(transformed, Opcodes.INVOKESPECIAL,
                                "net/minecraftforge/eventbus/ClassLoaderFactory", "createWrapper"),
                        "EventBus wrapper fast path does not call the direct wrapper factory");
            }
            if (target.className().equals(
                    "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader")) {
                require(hasInvocation(transformed, Opcodes.INVOKEVIRTUAL,
                                "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader",
                                "findLoadedClass"),
                        "EventBus ASMClassLoader does not reuse a previously defined wrapper");
            }
            if (target.className().equals(
                    "net/minecraftforge/fml/loading/moddiscovery/BackgroundScanHandler")) {
                require(hasSynchronizedMethod(transformed, "submitForScanning"),
                        "parallel scan submission does not protect shared lists");
                require(hasSynchronizedMethod(transformed, "addCompletedFile"),
                        "parallel scan completion does not protect shared lists");
            }
            new ClassReader(transformed).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
        require(messages.stream().noneMatch(message -> message.contains("failed")), "supported transform logged failure");
        require(Boolean.getBoolean("lightspeed.bootstrapAgent.resourceIndex"),
                "Forge resource index capability was not published");
    }

    private static void rejectsUnknownClassFingerprint() throws Exception {
        Target target = targets().get(0);
        byte[] changed = readEntry(target.jar(), target.entry());
        changed[changed.length - 1] ^= 1;
        List<String> messages = new ArrayList<>();
        byte[] transformed = new LauncherTransformer(messages::add)
                .transform(null, null, target.className(), null, null, changed);
        require(transformed == null, "unknown target fingerprint was transformed");
        require(messages.stream().anyMatch(message -> message.contains("unsupported fingerprint")),
                "unknown target fingerprint was not reported");
    }

    private static void rejectsUnknownNestedLoaderFingerprint() throws Exception {
        Target target = targets().stream()
                .filter(value -> value.className().equals(
                        "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader"))
                .findFirst().orElseThrow();
        byte[] changed = readEntry(target.jar(), target.entry());
        changed[changed.length - 1] ^= 1;
        List<String> messages = new ArrayList<>();
        byte[] transformed = new LauncherTransformer(messages::add)
                .transform(Object.class.getModule(), null, target.className(), null, null, changed);
        require(transformed == null, "unknown EventBus ASMClassLoader fingerprint was transformed");
        require(messages.stream().anyMatch(message -> message.contains("unsupported fingerprint")),
                "unknown EventBus ASMClassLoader fingerprint was not reported");
    }

    private static void unknownFingerprintDoesNotGrantModuleReads() throws Exception {
        AtomicInteger redefineCalls = new AtomicInteger();
        RuntimeModuleAccess.install(instrumentationSpy(redefineCalls, null));
        try {
            Target target = targets().stream()
                    .filter(value -> value.className().equals("cpw/mods/cl/ModuleClassLoader"))
                    .findFirst().orElseThrow();
            byte[] changed = readEntry(target.jar(), target.entry());
            changed[changed.length - 1] ^= 1;
            byte[] transformed = new LauncherTransformer(message -> {
            })
                    .transform(Object.class.getModule(), null, target.className(), null, null, changed);
            require(transformed == null, "unknown target fingerprint was transformed");
            require(redefineCalls.get() == 0,
                    "unknown target fingerprint changed named-module readability");
        } finally {
            RuntimeModuleAccess.install(null);
        }
    }

    private static void supportedFingerprintGrantsModuleRead() throws Exception {
        AtomicInteger redefineCalls = new AtomicInteger();
        @SuppressWarnings("unchecked")
        Set<Module>[] observedReads = new Set[]{Set.of()};
        RuntimeModuleAccess.install(instrumentationSpy(redefineCalls, observedReads));
        try {
            Target target = targets().stream()
                    .filter(value -> value.className().equals("cpw/mods/cl/ModuleClassLoader"))
                    .findFirst().orElseThrow();
            byte[] transformed = new LauncherTransformer(message -> {
            })
                    .transform(Object.class.getModule(), null, target.className(), null, null,
                            readEntry(target.jar(), target.entry()));
            require(transformed != null, "supported named-module target was not transformed");
            require(redefineCalls.get() == 1, "supported target did not grant one module read edge");
            require(observedReads[0].equals(Set.of(RuntimeModuleAccess.class.getModule())),
                    "supported target granted the wrong module read edge");
        } finally {
            RuntimeModuleAccess.install(null);
        }
    }

    private static void jdkOnlyPatchDoesNotGrantModuleRead() throws Exception {
        AtomicInteger redefineCalls = new AtomicInteger();
        RuntimeModuleAccess.install(instrumentationSpy(redefineCalls, null));
        try {
            Target target = targets().stream()
                    .filter(value -> value.className().equals(
                            "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader"))
                    .findFirst().orElseThrow();
            byte[] transformed = new LauncherTransformer(message -> {
            }).transform(Object.class.getModule(), null, target.className(), null, null,
                    readEntry(target.jar(), target.entry()));
            require(transformed != null, "supported JDK-only target was not transformed");
            require(redefineCalls.get() == 0,
                    "JDK-only EventBus patch granted an unnecessary Agent module read");
        } finally {
            RuntimeModuleAccess.install(null);
        }
    }

    private static java.lang.instrument.Instrumentation instrumentationSpy(
            AtomicInteger redefineCalls, Set<Module>[] observedReads) {
        return (java.lang.instrument.Instrumentation) Proxy.newProxyInstance(
                AgentRegressionCheck.class.getClassLoader(),
                new Class<?>[]{java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("redefineModule")) {
                        redefineCalls.incrementAndGet();
                        if (observedReads != null) {
                            @SuppressWarnings("unchecked")
                            Set<Module> reads = (Set<Module>) args[1];
                            observedReads[0] = Set.copyOf(reads);
                        }
                        return null;
                    }
                    Class<?> returnType = method.getReturnType();
                    if (!returnType.isPrimitive() || returnType == void.class) {
                        return null;
                    }
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return 0;
                });
    }

    private static void servicePrefilterIsFailOpen() throws IOException {
        Path directory = Files.createTempDirectory("lightspeed-agent-service-");
        try {
            Path ordinary = jar(directory.resolve("ordinary.jar"), Map.of("assets/example.txt", new byte[]{1}), false);
            Path service = jar(directory.resolve("service.jar"),
                    Map.of("META-INF/services/cpw.mods.modlauncher.api.ITransformationService", new byte[]{1}), false);
            Path module = jar(directory.resolve("module.jar"), Map.of("module-info.class", new byte[]{1}), false);
            Path multiRelease = jar(directory.resolve("multi-release.jar"), Map.of("assets/example.txt", new byte[]{1}), true);
            Path invalid = directory.resolve("invalid.jar");
            Files.writeString(invalid, "not a zip");

            require(!BootstrapHooks.mayProvideTransformerService(ordinary), "ordinary jar was not rejected");
            require(BootstrapHooks.mayProvideTransformerService(service), "service jar was rejected");
            require(BootstrapHooks.mayProvideTransformerService(module), "module jar was rejected");
            require(BootstrapHooks.mayProvideTransformerService(multiRelease), "multi-release jar did not fail open");
            require(BootstrapHooks.mayProvideTransformerService(invalid), "invalid jar did not fail open");
        } finally {
            deleteTree(directory);
        }
    }

    private static void resourceIndexHasNoFalseNegatives() throws IOException {
        Path directory = Files.createTempDirectory("lightspeed-agent-index-");
        try {
            Path archive = jar(directory.resolve("resources.jar"), Map.of(
                    "present.txt", new byte[]{1},
                    "nested/also-present.txt", new byte[]{2},
                    "nested-other/not-a-child.txt", new byte[]{3},
                    "example/first/Test.class", new byte[]{4},
                    "example/second/Other.class", new byte[]{5}), false);
            long qualificationChecks = ResourceMembershipIndex.qualificationChecks();
            try (FileSystem zip = FileSystems.newFileSystem(archive)) {
                Path root = zip.getPath("/");
                require(BootstrapHooks.mightContain(root, archive, "present.txt"), "present resource was rejected");
                require(BootstrapHooks.mightContain(root, archive, "nested/also-present.txt"), "nested resource was rejected");
                require(!BootstrapHooks.mightContain(root, archive, "missing.txt"), "missing resource was not rejected");
                int handle = BootstrapHooks.bindResourceIndex(root);
                require(handle != ResourceMembershipIndex.UNKNOWN, "exact resource root did not bind a handle");
                require(handle == BootstrapHooks.bindResourceIndex(root), "resource root handle was not stable");
                require(BootstrapHooks.resourceEntries(handle, "", "nested")
                                .equals(List.of("nested/also-present.txt")),
                        "prefix resource listing crossed a path-segment boundary");
                require(BootstrapHooks.resourcePackages(root)
                                .equals(Set.of("example.first", "example.second")),
                        "resource index did not reproduce SecureJar package discovery");
            }
            require(ResourceMembershipIndex.qualificationChecks() == qualificationChecks + 1,
                    "physical JAR qualification repeated for one indexed root");

            Path multiRelease = jar(directory.resolve("multi-release.jar"),
                    Map.of("META-INF/versions/17/only-versioned.txt", new byte[]{1}), true);
            try (FileSystem zip = FileSystems.newFileSystem(multiRelease)) {
                require(BootstrapHooks.mightContain(zip.getPath("/"), multiRelease, "only-versioned.txt"),
                        "multi-release index did not fail open");
                require(BootstrapHooks.resourcePackages(zip.getPath("/")) == null,
                        "multi-release package discovery did not fail open");
            }
            require(BootstrapHooks.mightContain(directory, directory, "anything"), "mutable directory did not fail open");

            Path first = jar(directory.resolve("first.jar"), Map.of(
                    "assets/first/one.txt", new byte[]{1},
                    "assets/first/hidden.txt", new byte[]{2}), false);
            Path second = jar(directory.resolve("second.jar"),
                    Map.of("assets/second/two.txt", new byte[]{3}), false);
            try (FileSystem zip = FileSystems.newFileSystem(first)) {
                Path root = zip.getPath("/");
                BootstrapHooks.registerResourceRoot(root, first,
                        (name, base) -> !name.endsWith("hidden.txt"), new Path[]{first, second});
                require(BootstrapHooks.mightContain(root, first, "assets/first/one.txt"),
                        "first union resource was rejected");
                require(BootstrapHooks.mightContain(root, first, "assets/second/two.txt"),
                        "second union resource was rejected");
                require(!BootstrapHooks.mightContain(root, first, "assets/first/hidden.txt"),
                        "filtered union resource was retained");
                int handle = BootstrapHooks.bindResourceIndex(root);
                require(BootstrapHooks.resourceNamespaces(handle, "assets")
                                .equals(Set.of("first", "second")),
                        "namespace index did not preserve all physical roots");
            }

            Path subRootArchive = jar(directory.resolve("sub-root.jar"), Map.of(
                    "assets/root/root.txt", new byte[]{1},
                    "optional/assets/child/inside.txt", new byte[]{2},
                    "optional/assets/child/nested/deep.txt", new byte[]{3}), false);
            try (FileSystem zip = FileSystems.newFileSystem(subRootArchive)) {
                Path root = zip.getPath("/");
                BootstrapHooks.mightContain(root, subRootArchive, "assets/root/root.txt");
                int rootHandle = BootstrapHooks.bindResourceIndex(root);
                int subRootHandle = BootstrapHooks.bindResourceIndex(root.resolve("optional"));
                require(BootstrapHooks.resourceEntries(rootHandle, "assets/root", "")
                                .equals(List.of("root.txt")),
                        "root view did not preserve root resources");
                List<String> subRootEntries = BootstrapHooks.resourceEntries(
                        subRootHandle, "assets/child", "nested");
                require(subRootEntries.equals(List.of("nested/deep.txt")),
                        "sub-root view leaked entries outside its logical root");
                try {
                    subRootEntries.set(0, "changed");
                    throw new AssertionError("resource view exposed mutable entries");
                } catch (UnsupportedOperationException expected) {
                    // Expected immutable view.
                }
            }
        } finally {
            deleteTree(directory);
        }
    }

    private static void packagedAgentIsIsolated() throws IOException {
        Path agent = Path.of(requireProperty("lightspeed.agent.jar"));
        try (JarFile jar = new JarFile(agent.toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            require("com.ccr4ft3r.lightspeed.bootstrap.LightspeedAgent".equals(attributes.getValue("Premain-Class")),
                    "agent manifest lacks Premain-Class");
            require("com.ccr4ft3r.lightspeed.bootstrap.LightspeedAgent".equals(attributes.getValue("Agent-Class")),
                    "agent manifest lacks Agent-Class");
            require("true".equals(attributes.getValue("Can-Retransform-Classes")),
                    "agent manifest must allow retransformation");
            require(jar.getEntry("com/ccr4ft3r/lightspeed/bootstrap/runtime/BootstrapHooks.class") != null,
                    "agent runtime hook is missing");
            require(jar.getEntry("com/ccr4ft3r/lightspeed/bootstrap/internal/asm/ClassReader.class") != null,
                    "ASM was not relocated");
            require(jar.getEntry("org/objectweb/asm/ClassReader.class") == null, "unrelocated ASM leaked into agent jar");
            require(jar.getEntry("META-INF/mods.toml") == null && jar.getEntry("META-INF/neoforge.mods.toml") == null,
                    "agent jar must not be discovered as a mod");
            byte[] entrypoint = jar.getInputStream(jar.getJarEntry(
                    "com/ccr4ft3r/lightspeed/bootstrap/LightspeedAgent.class")).readAllBytes();
            require(!new String(entrypoint, java.nio.charset.StandardCharsets.ISO_8859_1)
                            .contains("appendToBootstrapClassLoaderSearch"),
                    "Agent still appends its JAR to the bootstrap class path and disables custom-loader CDS");
        }
    }

    private static void eventMethodCachePreservesReflectionSemantics() throws Exception {
        Method inherited = EventMethodParent.class.getMethod("handle", String.class);
        long hits = EventMethodCache.hits();
        require(EventMethodCache.declaredMethod(EventMethodParent.class, inherited).orElseThrow().equals(inherited),
                "declared event method lookup changed the reflected method");
        require(EventMethodCache.declaredMethod(EventMethodParent.class, inherited).orElseThrow().equals(inherited),
                "cached event method lookup changed the reflected method");
        require(EventMethodCache.hits() == hits + 1, "event method cache did not record the repeated lookup");
        require(EventMethodCache.declaredMethod(String.class, inherited).isEmpty(),
                "missing declared event method did not remain empty");
        require(BootstrapHooks.canUseDirectEventWrapper(inherited),
                "public listener in an exported package did not use the direct wrapper path");
        Method hidden = HiddenEventMethodParent.class.getMethod("handle", String.class);
        require(!BootstrapHooks.canUseDirectEventWrapper(hidden),
                "non-public listener owner did not retain the ModLauncher fallback");
    }

    private static void ownerGuardRejectsMismatchedAgent() throws Exception {
        Path directory = Files.createTempDirectory("lightspeed-agent-owner-");
        String previousOwner = System.getProperty("lightspeed.agent.owner");
        try {
            Path agent = Path.of(requireProperty("lightspeed.agent.jar"));
            byte[] agentBytes = Files.readAllBytes(agent);
            String digest = sha256(agentBytes);
            Path owner = jar(directory.resolve("lightspeed-owner.jar"), Map.of(
                    "META-INF/mods.toml", new byte[]{1},
                    "META-INF/lightspeed/bootstrap-agent.jar", agentBytes), false);
            System.setProperty("lightspeed.agent.owner", owner.toString());
            require(LightspeedAgent.ownerMatchesAgent(digest), "matching embedded Agent was rejected");

            jar(owner, Map.of(
                    "META-INF/mods.toml", new byte[]{1},
                    "META-INF/lightspeed/bootstrap-agent.jar", new byte[]{9, 9, 9}), false);
            require(!LightspeedAgent.ownerMatchesAgent(digest), "mismatched embedded Agent was accepted");
        } finally {
            if (previousOwner == null) {
                System.clearProperty("lightspeed.agent.owner");
            } else {
                System.setProperty("lightspeed.agent.owner", previousOwner);
            }
            deleteTree(directory);
        }
    }

    private static void resourceImagePersistsRecordedBytes() throws IOException {
        Path directory = Files.createTempDirectory("lightspeed-agent-image-");
        String previous = System.getProperty("lightspeed.bootstrapCacheDir");
        try {
            System.setProperty("lightspeed.bootstrapCacheDir", directory.toString());
            byte[] expected = new byte[]{1, 2, 3, 4};
            Path archive = jar(directory.resolve("resource-image.jar"),
                    Map.of("assets/test/value.bin", expected), false);
            try (FileSystem zip = FileSystems.newFileSystem(archive)) {
                Path root = zip.getPath("/");
                BootstrapHooks.mightContain(root, archive, "assets/test/value.bin");
                String rootKey = ResourceMembershipIndex.persistentPathKey(root);
                String key = rootKey.substring(0, rootKey.indexOf('\0')) + "\0assets/test/value.bin";
                StartupResourceImage.recordResource(key, expected);
                require(MessageDigest.isEqual(expected, StartupResourceImage.resource(key)),
                        "recorded resource image bytes were not readable");
            }
            StartupResourceImage.persist();
            Path pointer = directory.resolve("resource-image-v3.bin.current");
            require(Files.size(pointer) > 0,
                    "resource image was not persisted");
            String generation = Files.readAllLines(pointer).get(0);
            require(Files.size(directory.resolve(generation)) > expected.length,
                    "resource image generation was not persisted");
        } finally {
            if (previous == null) {
                System.clearProperty("lightspeed.bootstrapCacheDir");
            } else {
                System.setProperty("lightspeed.bootstrapCacheDir", previous);
            }
            deleteTree(directory);
        }
    }

    private static void scanMetadataRoundTripsWithoutClassIo() throws IOException {
        Path directory = Files.createTempDirectory("lightspeed-agent-scan-");
        try {
            Path archive = jar(directory.resolve("scan.jar"), Map.of(
                    "nested/Test.class", new byte[]{1},
                    "nested/Second.class", new byte[]{2}), false);
            String unchangedKey;
            try (FileSystem zip = FileSystems.newFileSystem(archive)) {
                Path root = zip.getPath("/");
                BootstrapHooks.mightContain(root, archive, "nested/Test.class");
                Type classType = Type.getObjectType("nested/Test");
                Type secondType = Type.getObjectType("nested/Second");
                ModFileScanData source = new ModFileScanData();
                source.getClasses().add(new ModFileScanData.ClassData(
                        classType, Type.getType(Object.class), Set.of(Type.getType(Runnable.class))));
                source.getClasses().add(new ModFileScanData.ClassData(
                        secondType, Type.getType(Object.class), Set.of()));
                source.getAnnotations().add(new ModFileScanData.AnnotationData(
                        Type.getType(Deprecated.class), ElementType.TYPE, classType, null,
                        Map.of("name", "cached", "kind", new ModAnnotation.EnumHolder("Lsample/Kind;", "VALUE"),
                                "nested", Map.of("type", Type.getType(String.class)),
                                "values", List.of(1, true, "three"))));
                source.getAnnotations().add(new ModFileScanData.AnnotationData(
                        Type.getType(SuppressWarnings.class), ElementType.TYPE, secondType, null,
                        Map.of("value", List.of("unused"))));

                unchangedKey = ResourceMembershipIndex.persistentPathKey(root);
                ScanMetadataCache.record(root, source);
                ModFileScanData restored = new ModFileScanData();
                require(ScanMetadataCache.replay(root, restored), "aggregate scan metadata did not replay");
                require(restored.getClasses().equals(source.getClasses()), "scan class data changed during replay");
                require(restored.getAnnotations().size() == 2, "aggregate scan annotations were not restored");
                ModFileScanData.AnnotationData annotation = restored.getAnnotations().stream()
                        .filter(value -> value.annotationType().equals(Type.getType(Deprecated.class)))
                        .findFirst().orElseThrow();
                require(annotation.annotationType().equals(Type.getType(Deprecated.class)),
                        "scan annotation type changed during replay");
                ModAnnotation.EnumHolder enumValue = (ModAnnotation.EnumHolder) annotation.annotationData().get("kind");
                require(enumValue.getDesc().equals("Lsample/Kind;") && enumValue.getValue().equals("VALUE"),
                        "scan enum annotation value changed during replay");
            }

            try (FileSystem zip = FileSystems.newFileSystem(archive)) {
                Path root = zip.getPath("/");
                BootstrapHooks.mightContain(root, archive, "nested/Test.class");
                require(unchangedKey.equals(ResourceMembershipIndex.persistentPathKey(root)),
                        "unchanged Mod did not retain its scan key");
                require(ScanMetadataCache.replay(root, new ModFileScanData()),
                        "unchanged Mod did not retain its aggregate scan cache");
            }

            jar(archive, Map.of(
                    "nested/Test.class", new byte[]{1},
                    "nested/Second.class", new byte[]{2},
                    "nested/Changed.class", new byte[]{3}), false);
            try (FileSystem zip = FileSystems.newFileSystem(archive)) {
                Path root = zip.getPath("/");
                BootstrapHooks.mightContain(root, archive, "nested/Changed.class");
                require(!unchangedKey.equals(ResourceMembershipIndex.persistentPathKey(root)),
                        "changed Mod retained a stale scan key");
                require(!ScanMetadataCache.replay(root, new ModFileScanData()),
                        "changed Mod replayed stale aggregate scan metadata");
            }
        } finally {
            deleteTree(directory);
        }
    }

    private static List<Target> targets() {
        return List.of(
                target("lightspeed.target.forge.production", "net/minecraftforge/fml/loading/ModDirTransformerDiscoverer",
                        "fe801f95d52cff0afda4a64768a77a6567fcb8a55cbeed1efee491e2098142f3", "mayProvideTransformerService"),
                target("lightspeed.target.forge.development", "net/minecraftforge/fml/loading/ModDirTransformerDiscoverer",
                        "b9991df65f099d651b4d07729412b4c7f6b2815e6eb1dc6b65f43a5b724b72a9", "mayProvideTransformerService"),
                target("lightspeed.target.neoforge", "net/neoforged/fml/loading/ModDirTransformerDiscoverer",
                        "7a94a5ce380ea983a337d5a8e3ea3eb84e88b584fc172c6ab314948967c9083c", "mayProvideTransformerService"),
                target("lightspeed.target.securejar2", "cpw/mods/jarhandling/impl/Jar",
                        "bba6a4ee9327d364967a3cfec4707d695d5962cb42a20a4b212a26434a5b9055",
                        "registerResourceRoot", "mightContain", "resourcePackages"),
                target("lightspeed.target.securejar2", "cpw/mods/cl/ModuleClassLoader",
                        "62e3eaa069098d55f5da70e6dbc2a35a1e622d68804b2af6049583151bcb6f16",
                        "rawClassBytes", "recordRawClassBytes"),
                target("lightspeed.target.securejar3", "cpw/mods/cl/ModuleClassLoader",
                        "1ea195fe3b32c95e7232f49c14750b9245a664e3b66c89e298c31371d8b36376",
                        "rawClassBytes", "recordRawClassBytes"),
                target("lightspeed.target.forge.production", "net/minecraftforge/fml/loading/moddiscovery/Scanner",
                        "40475b4b77a9709ac07ef64f00aa234aad403c0f82c4e65b8329ee03f379f495",
                        "replayScanMetadata", "recordScanMetadata"),
                target("lightspeed.target.forge.production",
                        "net/minecraftforge/fml/loading/moddiscovery/BackgroundScanHandler",
                        "a6e143da3b8fe5045e61e0c9b469555a69296eff2105e4b936a47f96ba12c613",
                        "createModScanExecutor"),
                target("lightspeed.target.forge.development",
                        "net/minecraftforge/fml/loading/moddiscovery/BackgroundScanHandler",
                        "a6e143da3b8fe5045e61e0c9b469555a69296eff2105e4b936a47f96ba12c613",
                        "createModScanExecutor"),
                target("lightspeed.target.forge.game", "net/minecraftforge/registries/ObjectHolderRegistry",
                        "7d3f5fb619d52c448b9f2dfccea52c676144fb4de38b0c36df0ee52cdd370ba4",
                        "applyObjectHolders", "objectHoldersChanged"),
                target("lightspeed.target.eventbus", "net/minecraftforge/eventbus/EventBus",
                        "85c5db423fac7eb69107993923aa8a1967d21916fd5c9b32d36332700e31e3d0",
                        "declaredEventMethod"),
                target("lightspeed.target.eventbus", "net/minecraftforge/eventbus/ModLauncherFactory",
                        "437ddbbab024eba0c41c969f74dd656cf077433fc6535264689982f211ff5676",
                        "canUseDirectEventWrapper"),
                target("lightspeed.target.eventbus.old", "net/minecraftforge/eventbus/ModLauncherFactory",
                        "eecfddd6384bf97f6769da1e80a427bda678f093d46e3e77a27b7be696b3e46b",
                        "canUseDirectEventWrapper"),
                target("lightspeed.target.eventbus.mid", "net/minecraftforge/eventbus/ModLauncherFactory",
                        "3d562e4869935631d040a160b8f7c8949570eae38b23d0551c1243f0c995f38a",
                        "canUseDirectEventWrapper"),
                target("lightspeed.target.eventbus.current", "net/minecraftforge/eventbus/ModLauncherFactory",
                        "b884ef498fbdbfad4ce9b1f010993baf32ea150dc1408070086a70c638ab2806",
                        "canUseDirectEventWrapper"),
                target("lightspeed.target.eventbus.old",
                        "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader",
                        "f6087d2c3e14c37ff63da95ae74dcee33f4f1983da1662f535e4c562dd0b0ec9"),
                target("lightspeed.target.eventbus",
                        "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader",
                        "f6087d2c3e14c37ff63da95ae74dcee33f4f1983da1662f535e4c562dd0b0ec9"),
                target("lightspeed.target.eventbus.mid",
                        "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader",
                        "4e87d4ece3377a908b5a4801b993445bc6dee8231b82ce01226feb7df8b63776"),
                target("lightspeed.target.eventbus.current",
                        "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader",
                        "e37ab24ca16f279c39b13c78e28ffaba8385f7bf6a94efba68e9c565ff16cb03")
        );
    }

    private static Target target(String property, String className, String sha256, String... hookNames) {
        return new Target(Path.of(requireProperty(property)), className + ".class", className, sha256, List.of(hookNames));
    }

    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("missing system property " + name);
        }
        return value;
    }

    private static byte[] readEntry(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(name);
            require(entry != null, "missing target class " + name + " in " + jar);
            return file.getInputStream(entry).readAllBytes();
        }
    }

    private static boolean hasHookCall(byte[] bytes, String hookName) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && HOOK_OWNER.equals(owner) && hookName.equals(name)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasHookOwner(byte[] bytes) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                boolean isInterface) {
                        if (HOOK_OWNER.equals(owner)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasInvocation(byte[] bytes, int expectedOpcode, String expectedOwner, String expectedName) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                boolean isInterface) {
                        if (opcode == expectedOpcode && owner.equals(expectedOwner) && name.equals(expectedName)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasSynchronizedMethod(byte[] bytes, String methodName) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if (name.equals(methodName) && (access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static Path jar(Path path, Map<String, byte[]> entries, boolean multiRelease) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().putValue("Multi-Release", "true");
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Target(Path jar, String entry, String className, String sha256, List<String> hookNames) {
    }

    public static class EventMethodParent {
        public void handle(String value) {
        }
    }

    static class HiddenEventMethodParent {
        public void handle(String value) {
        }
    }
}
