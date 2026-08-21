package com.ccr4ft3r.lightspeed.bootstrap;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.module.ModuleResolutionCache;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class ModuleResolutionCacheCheck {
    private ModuleResolutionCacheCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("lightspeed-module-plan-check");
        System.setProperty("lightspeed.bootstrapCacheDir", directory.toString());
        try {
            ModuleFinder empty = ModuleFinder.of();
            List<Configuration> parents = List.of(ModuleLayer.boot().configuration());
            Configuration first = ModuleResolutionCache.resolveAndBind(empty, parents, empty, List.of());
            Configuration second = ModuleResolutionCache.resolveAndBind(empty, parents, empty, List.of());
            require(first.modules().equals(second.modules()), "cached module graph differs from the cold graph");
            require(ModuleResolutionCache.misses() == 1, "cold module resolution was not recorded once");
            require(ModuleResolutionCache.hits() == 1, "warm module resolution did not use the cached root closure");

            Configuration parentWithoutUses = parent(false);
            Configuration parentWithUses = parent(true);
            ModuleFinder provider = new MemoryFinder(ModuleDescriptor.newModule("provider")
                    .requires("service.api")
                    .packages(Set.of("impl"))
                    .provides("test.Service", List.of("impl.Provider"))
                    .build());
            Configuration withoutProvider = ModuleResolutionCache.resolveAndBind(
                    provider, List.of(parentWithoutUses), empty, List.of());
            Configuration withProvider = ModuleResolutionCache.resolveAndBind(
                    provider, List.of(parentWithUses), empty, List.of());
            Configuration warmWithProvider = ModuleResolutionCache.resolveAndBind(
                    provider, List.of(parentWithUses), empty, List.of());
            require(withoutProvider.findModule("provider").isEmpty(),
                    "provider was bound without a parent uses directive");
            require(withProvider.findModule("provider").isPresent(),
                    "parent uses directive did not bind its provider");
            require(warmWithProvider.findModule("provider").isPresent(),
                    "cached service closure omitted the provider");
            require(ModuleResolutionCache.misses() == 3,
                    "parent descriptor changes did not produce independent cold plans");
            require(ModuleResolutionCache.hits() == 2,
                    "service-bound module plan did not hit on the matching parent descriptor");
            ModuleResolutionCache.persist();
            require(Files.isRegularFile(directory.resolve("module-resolution-v1.bin")),
                    "module resolution plan was not persisted");
            System.out.println("MODULE_RESOLUTION_CACHE_OK");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    private static Configuration parent(boolean usesService) {
        ModuleDescriptor serviceApi = ModuleDescriptor.newModule("service.api")
                .packages(Set.of("test"))
                .exports("test")
                .build();
        ModuleDescriptor.Builder builder = ModuleDescriptor.newModule("parent").requires("service.api");
        if (usesService) {
            builder.uses("test.Service");
        }
        ModuleFinder finder = new MemoryFinder(serviceApi, builder.build());
        return Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()), ModuleFinder.of(),
                List.of("parent"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class MemoryFinder implements ModuleFinder {
        private final Map<String, ModuleReference> references;

        private MemoryFinder(ModuleDescriptor... descriptors) {
            references = Stream.of(descriptors).collect(java.util.stream.Collectors.toUnmodifiableMap(
                    ModuleDescriptor::name, MemoryReference::new));
        }

        @Override
        public Optional<ModuleReference> find(String name) {
            return Optional.ofNullable(references.get(name));
        }

        @Override
        public Set<ModuleReference> findAll() {
            return Set.copyOf(references.values());
        }
    }

    private static final class MemoryReference extends ModuleReference {
        private MemoryReference(ModuleDescriptor descriptor) {
            super(descriptor, URI.create("memory:///" + descriptor.name()));
        }

        @Override
        public ModuleReader open() {
            return new ModuleReader() {
                @Override
                public Optional<URI> find(String name) {
                    return Optional.empty();
                }

                @Override
                public Optional<InputStream> open(String name) {
                    return Optional.empty();
                }

                @Override
                public Optional<ByteBuffer> read(String name) {
                    return Optional.empty();
                }

                @Override
                public void release(ByteBuffer buffer) {
                }

                @Override
                public Stream<String> list() {
                    return Stream.empty();
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
