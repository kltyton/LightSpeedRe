package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.image.StartupResourceImage;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;

public final class ResourceMembershipIndex {
    public static final int UNKNOWN = -1;
    private static final ConcurrentHashMap<FileSystem, RootRegistration> ROOTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<FileSystem, CompletableFuture<JarResourceIndex>> INDEXES = new ConcurrentHashMap<>();
    private static final LongAdder QUERIES = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder INDEXED_ENTRIES = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder QUALIFICATION_CHECKS = new LongAdder();

    private ResourceMembershipIndex() {
    }

    public static void register(Path root, Path primary, BiPredicate<String, String> filter, Path[] paths) {
        if (root == null || !isSupportedRoot(root)) {
            return;
        }
        Path[] existing = Arrays.stream(paths == null ? new Path[0] : paths)
                .filter(Objects::nonNull)
                .filter(Files::exists)
                .toArray(Path[]::new);
        if (existing.length == 0 && primary != null && Files.exists(primary)) {
            existing = new Path[]{primary};
        }
        ROOTS.putIfAbsent(root.getFileSystem(), new RootRegistration(root, primary, filter, existing));
    }

    public static boolean mightContain(Path root, Path primary, String name) {
        QUERIES.increment();
        if (name == null || root == null) {
            return true;
        }
        RootRegistration registration = ROOTS.computeIfAbsent(root.getFileSystem(),
                ignored -> new RootRegistration(root, primary, null, primary == null ? new Path[0] : new Path[]{primary}));
        JarResourceIndex index = index(registration);
        if (!index.isExact()) {
            return true;
        }
        boolean result = index.contains(normalize(name));
        if (!result) {
            REJECTED.increment();
        }
        return result;
    }

    public static String[] entries(Path path, String basePrefix, String requestedPath) {
        JarResourceIndex index = indexFor(path);
        return index == null ? null : index.entries(normalizePrefix(basePrefix), normalizePrefix(requestedPath));
    }

    public static int contains(Path path, String name) {
        JarResourceIndex index = indexFor(path);
        if (index == null || !index.isExact()) {
            return UNKNOWN;
        }
        return index.contains(normalize(name)) ? 1 : 0;
    }

    public static String[] namespaces(Path path, String directory) {
        JarResourceIndex index = indexFor(path);
        return index == null ? null : index.namespaces(normalizePrefix(directory));
    }

    public static byte[] resourceBytes(Path path, String name) {
        return StartupResourceImage.get(resourceKey(path, name));
    }

    public static void recordResourceBytes(Path path, String name, byte[] bytes) {
        StartupResourceImage.record(resourceKey(path, name), bytes);
    }

    private static String resourceKey(Path path, String name) {
        if (path == null || name == null) {
            return null;
        }
        RootRegistration registration = ROOTS.get(path.getFileSystem());
        if (registration == null || registration.paths().length != 1 || !index(registration).isExact()) {
            return null;
        }
        Path source = registration.paths()[0];
        return source.toAbsolutePath().normalize() + "\0" + normalize(name);
    }

    private static JarResourceIndex indexFor(Path path) {
        if (path == null) {
            return null;
        }
        RootRegistration registration = ROOTS.get(path.getFileSystem());
        if (registration == null) {
            return null;
        }
        JarResourceIndex index = index(registration);
        return index.isExact() ? index : null;
    }

    private static JarResourceIndex index(RootRegistration registration) {
        FileSystem fileSystem = registration.root().getFileSystem();
        CompletableFuture<JarResourceIndex> existing = INDEXES.get(fileSystem);
        if (existing != null) {
            return existing.join();
        }
        CompletableFuture<JarResourceIndex> created = new CompletableFuture<>();
        CompletableFuture<JarResourceIndex> future = INDEXES.putIfAbsent(fileSystem, created);
        if (future == null) {
            future = created;
            try {
                QUALIFICATION_CHECKS.increment();
                JarResourceIndex index = JarResourceIndex.build(registration);
                if (index.isExact()) {
                    INDEXED_ENTRIES.add(index.size());
                }
                created.complete(index);
            } catch (RuntimeException exception) {
                FAILURES.increment();
                created.complete(JarResourceIndex.unsupported());
            }
        }
        return future.join();
    }

    private static boolean isSupportedRoot(Path root) {
        String scheme = root.getFileSystem().provider().getScheme();
        return "union".equalsIgnoreCase(scheme) || "jar".equalsIgnoreCase(scheme);
    }

    static String normalize(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String normalizePrefix(String value) {
        String normalized = normalize(value == null ? "" : value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static long queries() {
        return QUERIES.sum();
    }

    public static long rejected() {
        return REJECTED.sum();
    }

    public static long indexedEntries() {
        return INDEXED_ENTRIES.sum();
    }

    public static long failures() {
        return FAILURES.sum();
    }

    public static int indexCount() {
        return INDEXES.size();
    }

    public static long qualificationChecks() {
        return QUALIFICATION_CHECKS.sum();
    }

    record RootRegistration(Path root, Path primary, BiPredicate<String, String> filter, Path[] paths) {
    }
}
