package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.image.StartupResourceImage;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.module.ModuleReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;

public final class ResourceMembershipIndex {
    public static final int UNKNOWN = -1;
    private static final ConcurrentHashMap<FileSystem, RootRegistration> ROOTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<FileSystem, CompletableFuture<JarResourceIndex>> INDEXES = new ConcurrentHashMap<>();
    private static final Object VIEW_LOCK = new Object();
    private static volatile JarResourceView[] VIEWS = new JarResourceView[0];
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
        ROOTS.putIfAbsent(root.getFileSystem(),
                new RootRegistration(root, primary, filter, true, existing, new ConcurrentHashMap<>()));
    }

    /** Returns the registered native source count, or UNKNOWN when the registration is incomplete. */
    public static int sourceCount(Path root) {
        RootRegistration registration = registration(root);
        return registration != null && registration.filterKnown() && registration.paths().length > 0
                ? registration.paths().length : UNKNOWN;
    }

    public static boolean mightContain(Path root, Path primary, String name) {
        QUERIES.increment();
        if (name == null || root == null) {
            return true;
        }
        RootRegistration registration = ROOTS.computeIfAbsent(root.getFileSystem(), ignored -> new RootRegistration(
                root, primary, null, false, primary == null ? new Path[0] : new Path[]{primary}, new ConcurrentHashMap<>()));
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

    /** Null keeps native lookup for unsupported roots, directories and noncanonical names. */
    public static Optional<Path> findFile(Path root, Path primary, String name) {
        QUERIES.increment();
        if (name == null || root == null || !root.getFileSystem().isOpen()) {
            return null;
        }
        RootRegistration registration = ROOTS.computeIfAbsent(root.getFileSystem(), ignored -> new RootRegistration(
                root, primary, null, false, primary == null ? new Path[0] : new Path[]{primary}, new ConcurrentHashMap<>()));
        String normalized = normalize(name);
        Path queryPath = null;
        if (normalized.startsWith(".") || normalized.contains("/.") || normalized.contains("//")
                || normalized.endsWith("/")) {
            queryPath = root.getFileSystem().getPath(name);
            if (!queryPath.equals(queryPath.normalize()) || !normalized.equals(normalize(queryPath.toString()))) {
                return null;
            }
        }
        JarResourceIndex index = index(registration);
        if (!index.isExact()) {
            return null;
        }
        if (index.containsFile(normalized)) {
            // A merged index cannot reproduce per-source selection and callback order.
            if (!registration.filterKnown() || registration.paths().length != 1) {
                return null;
            }
            Path relative = queryPath == null ? root.getFileSystem().getPath(name) : queryPath;
            if (!relative.equals(relative.normalize())) {
                return null;
            }
            BiPredicate<String, String> filter = registration.filter();
            if (filter != null) {
                String base = registration.paths()[0].toString().replace('\\', '/');
                if (base.length() > 1 && base.startsWith("/")) base = base.substring(1);
                if (!filter.test(normalized, base)) {
                    REJECTED.increment();
                    return Optional.empty();
                }
            }
            return Optional.of(root.resolve(relative));
        }
        if (index.contains(normalized)) {
            return null;
        }
        REJECTED.increment();
        return Optional.empty();
    }

    public static int bind(Path path) {
        RootRegistration registration = registration(path);
        if (registration == null) {
            return UNKNOWN;
        }
        String prefix = relativePrefix(registration, path);
        if (prefix == null) {
            return UNKNOWN;
        }
        return registration.views().computeIfAbsent(prefix, ignored -> {
            JarResourceIndex index = index(registration);
            if (!index.isExact()) {
                return UNKNOWN;
            }
            String imageSource = registration.paths().length == 1 ? index.sourceIdentity() : null;
            return publish(index.view(prefix, imageSource));
        });
    }

    public static List<String> entries(int handle, String basePrefix, String requestedPath) {
        JarResourceView view = view(handle);
        return view == null ? null : view.entries(normalizePrefix(basePrefix), normalizePrefix(requestedPath));
    }

    public static int contains(int handle, String name) {
        JarResourceView view = view(handle);
        if (view == null) {
            return UNKNOWN;
        }
        return view.contains(normalize(name)) ? 1 : 0;
    }

    public static Set<String> namespaces(int handle, String directory) {
        JarResourceView view = view(handle);
        return view == null ? null : view.namespaces(normalizePrefix(directory));
    }

    public static byte[] resourceBytes(int handle, String name) {
        JarResourceView view = view(handle);
        return view == null ? null : StartupResourceImage.resource(view.imageKey(normalize(name)));
    }

    public static void recordResourceBytes(int handle, String name, byte[] bytes) {
        JarResourceView view = view(handle);
        if (view != null) {
            StartupResourceImage.recordResource(view.imageKey(normalize(name)), bytes);
        }
    }

    public static String persistentPathKey(Path path) {
        RootRegistration registration = registration(path);
        if (registration == null) {
            return null;
        }
        String relative = relativePrefix(registration, path);
        JarResourceIndex index = index(registration);
        if (relative == null || !index.isExact()) {
            return null;
        }
        return index.sourceIdentity() + "\0" + relative;
    }

    public static String physicalJarScanKey(Path path) {
        if (path == null) {
            return null;
        }
        RootRegistration registration = new RootRegistration(path, path, (name, base) -> true, true,
                new Path[]{path}, new ConcurrentHashMap<>());
        JarResourceIndex index = JarResourceIndex.build(registration);
        return index.isExact() ? index.sourceIdentity() + "\0" : null;
    }

    static String persistentFileKey(Path path) {
        RootRegistration registration = registration(path);
        if (registration == null) return null;
        String relative = relativePrefix(registration, path);
        JarResourceIndex index = index(registration);
        return relative != null && index.isExact() && index.containsFile(relative)
                ? index.sourceIdentity() + '\0' + relative : null;
    }

    public static Set<String> activeSourceIdentities() {
        return ROOTS.values().stream()
                .map(ResourceMembershipIndex::index)
                .filter(JarResourceIndex::isExact)
                .map(JarResourceIndex::sourceIdentity)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static Set<String> packages(Path root) {
        RootRegistration registration = registration(root);
        if (registration == null) {
            return null;
        }
        JarResourceIndex index = index(registration);
        return index.isExact() ? new HashSet<>(index.packages()) : null;
    }

    public static String sourceIdentity(ModuleReference reference) {
        if (reference == null || reference.location().isEmpty()) {
            return null;
        }
        try {
            Path path = Path.of(reference.location().orElseThrow());
            RootRegistration registration = registration(path);
            if (registration == null) {
                return null;
            }
            JarResourceIndex index = index(registration);
            return index.isExact() ? index.sourceIdentity() : null;
        } catch (RuntimeException exception) {
            FAILURES.increment();
            return null;
        }
    }

    public static int classEntryCount(Path path) {
        RootRegistration registration = registration(path);
        if (registration == null) {
            return UNKNOWN;
        }
        JarResourceIndex index = index(registration);
        return index.isExact() ? index.classEntryCount() : UNKNOWN;
    }

    private static RootRegistration registration(Path path) {
        if (path == null) {
            return null;
        }
        return ROOTS.get(path.getFileSystem());
    }

    private static String relativePrefix(RootRegistration registration, Path path) {
        try {
            Path root = registration.root().toAbsolutePath().normalize();
            Path candidate = path.toAbsolutePath().normalize();
            if (!candidate.startsWith(root)) {
                return null;
            }
            return normalize(root.relativize(candidate).toString());
        } catch (RuntimeException exception) {
            FAILURES.increment();
            return null;
        }
    }

    private static int publish(JarResourceView view) {
        if (view == null) {
            return UNKNOWN;
        }
        synchronized (VIEW_LOCK) {
            JarResourceView[] current = VIEWS;
            int handle = current.length;
            JarResourceView[] expanded = Arrays.copyOf(current, handle + 1);
            expanded[handle] = view;
            VIEWS = expanded;
            return handle;
        }
    }

    private static JarResourceView view(int handle) {
        JarResourceView[] views = VIEWS;
        return handle < 0 || handle >= views.length ? null : views[handle];
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

    public static String normalize(String value) {
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

    public static int viewCount() {
        return VIEWS.length;
    }

    public static long qualificationChecks() {
        return QUALIFICATION_CHECKS.sum();
    }

    record RootRegistration(Path root, Path primary, BiPredicate<String, String> filter, boolean filterKnown, Path[] paths,
                            ConcurrentHashMap<String, Integer> views) {
    }
}
