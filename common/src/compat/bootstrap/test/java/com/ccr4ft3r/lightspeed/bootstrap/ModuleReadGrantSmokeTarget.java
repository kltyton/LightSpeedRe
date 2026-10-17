package com.ccr4ft3r.lightspeed.bootstrap;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;

import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class ModuleReadGrantSmokeTarget {
    private ModuleReadGrantSmokeTarget() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("lightspeed-module-read-");
        try {
            ModuleFinder finder = buildFixtureModule(directory);
            Configuration configuration = ModuleLayer.boot().configuration()
                    .resolve(finder, ModuleFinder.of(), Set.of("fixture.bridge"));
            ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(
                    configuration, ClassLoader.getSystemClassLoader());
            Module source = layer.findModule("fixture.bridge").orElseThrow();
            Module target = BootstrapHooks.class.getModule();
            require(source.isNamed() && !source.canRead(target),
                    "fixture module unexpectedly read the Agent module before the grant");
            RuntimeModuleAccess.grantReadAccess(source);
            require(source.canRead(target), "Instrumentation did not grant the named-module read edge");

            Class<?> bridge = layer.findLoader("fixture.bridge").loadClass("fixture.bridge.Bridge");
            Object result = bridge.getMethod("call").invoke(null);
            require(BootstrapHooks.class.getName().equals(result),
                    "named-module fixture did not resolve the system-loader Agent hook");
            System.out.println("NAMED_MODULE_AGENT_READ_OK");
        } finally {
            deleteTree(directory);
        }
    }

    private static ModuleFinder buildFixtureModule(Path directory) throws Exception {
        Path plainSource = directory.resolve("plain-src/fixture/bridge/Bridge.java");
        Path plainClasses = directory.resolve("plain-classes");
        Files.createDirectories(plainSource.getParent());
        Files.createDirectories(plainClasses);
        Files.writeString(plainSource, """
                package fixture.bridge;
                public final class Bridge {
                    public static String call() {
                        return com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks.class.getName();
                    }
                }
                """);
        compile("-classpath", requireProperty("lightspeed.agent.jar"), "-d", plainClasses.toString(),
                plainSource.toString());
        byte[] bridge = Files.readAllBytes(plainClasses.resolve("fixture/bridge/Bridge.class"));
        ModuleDescriptor descriptor = ModuleDescriptor.newModule("fixture.bridge")
                .packages(Set.of("fixture.bridge"))
                .exports("fixture.bridge")
                .build();
        return new MemoryModuleFinder(descriptor, Map.of("fixture/bridge/Bridge.class", bridge));
    }

    private static void compile(String... arguments) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "smoke test requires a JDK compiler");
        int exitCode = compiler.run(null, System.out, System.err, arguments);
        require(exitCode == 0, "fixture compilation failed with exit code " + exitCode);
    }

    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("missing system property " + name);
        }
        return value;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> children;
        try (var paths = Files.walk(root)) {
            children = paths.filter(path -> !path.equals(root)).sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : children) {
            Files.deleteIfExists(path);
        }
        Files.deleteIfExists(root);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class MemoryModuleFinder implements ModuleFinder {
        private final ModuleReference reference;

        private MemoryModuleFinder(ModuleDescriptor descriptor, Map<String, byte[]> resources) {
            reference = new MemoryModuleReference(descriptor, resources);
        }

        @Override
        public Optional<ModuleReference> find(String name) {
            return reference.descriptor().name().equals(name) ? Optional.of(reference) : Optional.empty();
        }

        @Override
        public Set<ModuleReference> findAll() {
            return Set.of(reference);
        }
    }

    private static final class MemoryModuleReference extends ModuleReference {
        private final Map<String, byte[]> resources;

        private MemoryModuleReference(ModuleDescriptor descriptor, Map<String, byte[]> resources) {
            super(descriptor, URI.create("memory:///" + descriptor.name()));
            this.resources = resources;
        }

        @Override
        public ModuleReader open() {
            return new ModuleReader() {
                @Override
                public Optional<URI> find(String name) {
                    return resources.containsKey(name)
                            ? Optional.of(URI.create("memory:///" + name))
                            : Optional.empty();
                }

                @Override
                public Optional<java.io.InputStream> open(String name) {
                    byte[] bytes = resources.get(name);
                    return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
                }

                @Override
                public Optional<ByteBuffer> read(String name) {
                    byte[] bytes = resources.get(name);
                    return bytes == null ? Optional.empty() : Optional.of(ByteBuffer.wrap(bytes));
                }

                @Override
                public void release(ByteBuffer buffer) {
                }

                @Override
                public Stream<String> list() {
                    return resources.keySet().stream();
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
