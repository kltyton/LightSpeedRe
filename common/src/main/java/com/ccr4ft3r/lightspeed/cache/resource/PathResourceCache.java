package com.ccr4ft3r.lightspeed.cache.resource;

import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Per-pack discovery state; native adapters own archive identities and persistence bindings. */
public final class PathResourceCache {
    private volatile Map<String, Path> resolved;
    private volatile Map<PackType, Set<String>> namespaces;
    private volatile Map<PackType, Map<String, List<String>>> lists;
    private volatile Map<PackType, Map<String, ResourcePathIndex>> sortedIndexes;
    private volatile Map<PackType, Map<String, Set<String>>> pathSets;
    private volatile Map<PackType, Map<String, OrderedResourcePathIndex>> orderedIndexes;
    private volatile Map<String, CompletableFuture<List<String>>> scans;
    private volatile ResourcePathValidationCache validation;

    public Map<String, Path> resolvedPaths() {
        if (resolved == null) synchronized (this) {
            if (resolved == null) resolved = new ConcurrentHashMap<>();
        }
        return resolved;
    }

    public Path resolved(String... parts) {
        String key = Arrays.toString(parts);
        Path path = resolvedPaths().get(key);
        if (path != null && !isUsablePath(path)) {
            resolvedPaths().remove(key);
            return null;
        }
        return path;
    }

    public Map<PackType, Set<String>> namespaces() {
        if (namespaces == null) synchronized (this) {
            if (namespaces == null) namespaces = new ConcurrentHashMap<>();
        }
        return namespaces;
    }

    public void bindNamespaces(Map<PackType, Set<String>> values) {
        namespaces = values;
    }

    public Map<PackType, Map<String, List<String>>> lists() {
        if (lists == null) synchronized (this) {
            if (lists == null) {
                Map<PackType, Map<String, List<String>>> values = new ConcurrentHashMap<>();
                for (PackType type : PackType.values()) values.put(type, new ConcurrentHashMap<>());
                lists = values;
            }
        }
        return lists;
    }

    public void bindLists(Map<PackType, Map<String, List<String>>> values) {
        lists = values;
    }

    public List<String> cached(PackType type, String namespace) {
        return lists().computeIfAbsent(type, ignored -> new ConcurrentHashMap<>()).get(namespace);
    }

    public List<String> discover(PackType type, String namespace, Path root, boolean sorted) {
        Map<String, List<String>> pathsByNamespace = lists().computeIfAbsent(type, ignored -> new ConcurrentHashMap<>());
        List<String> paths = pathsByNamespace.get(namespace);
        if (paths != null) {
            if (sorted) index(type, namespace, paths);
            else pathSet(type, namespace, paths);
            return paths;
        }
        paths = scanFiles(root);
        if (paths != null) {
            if (sorted) {
                ResourcePathIndex index = ResourcePathIndex.from(paths);
                paths = index.entries();
                pathsByNamespace.put(namespace, paths);
                sortedIndexes().computeIfAbsent(type, ignored -> new ConcurrentHashMap<>()).put(namespace, index);
            } else {
                pathsByNamespace.put(namespace, paths);
                pathSet(type, namespace, paths);
            }
        }
        return paths;
    }

    public ResourcePathIndex index(PackType type, String namespace, List<String> paths) {
        return sortedIndexes().computeIfAbsent(type, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(namespace, ignored -> ResourcePathIndex.fromSortedDistinct(paths));
    }

    public Set<String> pathSet(PackType type, String namespace, List<String> paths) {
        return pathSets().computeIfAbsent(type, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(namespace, ignored -> Set.copyOf(paths));
    }

    public Map<PackType, Map<String, ResourcePathIndex>> sortedIndexes() {
        if (sortedIndexes == null) synchronized (this) {
            if (sortedIndexes == null) sortedIndexes = new ConcurrentHashMap<>();
        }
        return sortedIndexes;
    }

    public Map<PackType, Map<String, Set<String>>> pathSets() {
        if (pathSets == null) synchronized (this) {
            if (pathSets == null) pathSets = new ConcurrentHashMap<>();
        }
        return pathSets;
    }

    public Map<PackType, Map<String, OrderedResourcePathIndex>> orderedIndexes() {
        if (orderedIndexes == null) synchronized (this) {
            if (orderedIndexes == null) orderedIndexes = new ConcurrentHashMap<>();
        }
        return orderedIndexes;
    }

    public Map<String, CompletableFuture<List<String>>> scans() {
        if (scans == null) synchronized (this) {
            if (scans == null) scans = new ConcurrentHashMap<>();
        }
        return scans;
    }

    public ResourcePathValidationCache validation() {
        if (validation == null) synchronized (this) {
            if (validation == null) validation = new ResourcePathValidationCache();
        }
        return validation;
    }

    public static List<String> scanFiles(Path root) {
        try (Stream<Path> paths = Files.find(root, Integer.MAX_VALUE, (path, attributes) -> attributes.isRegularFile())) {
            String separator = root.getFileSystem().getSeparator();
            return paths.map(root::relativize).map(Path::toString)
                    .map(path -> path.replace(separator, "/")).collect(Collectors.toList());
        } catch (NoSuchFileException ignored) {
            return Collections.emptyList();
        } catch (IOException exception) {
            LogUtils.getLogger().error("Failed to list path {}", root, exception);
            return null;
        } catch (RuntimeException exception) {
            LogUtils.getLogger().warn("Failed to list path {}; falling back to vanilla resource enumeration", root, exception);
            return null;
        }
    }

    public static Set<String> scanNamespaces(PackType type, Function<String[], Path> resolver) {
        try {
            Path root = resolver.apply(new String[]{type.getDirectory()});
            try (Stream<Path> paths = Files.walk(root, 1)) {
                return paths.filter(Files::isDirectory).map(root::relativize)
                        .filter(path -> path.getNameCount() > 0)
                        .map(path -> path.toString().replaceAll("/$", ""))
                        .filter(namespace -> !namespace.isEmpty()).collect(Collectors.toSet());
            }
        } catch (IOException exception) {
            return type == PackType.SERVER_DATA ? scanNamespaces(PackType.CLIENT_RESOURCES, resolver) : Collections.emptySet();
        }
    }

    public static boolean isUsablePath(Path path) {
        try {
            return path != null && path.getFileSystem().isOpen();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
