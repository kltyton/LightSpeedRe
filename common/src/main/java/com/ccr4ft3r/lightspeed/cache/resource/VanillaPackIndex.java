package com.ccr4ft3r.lightspeed.cache.resource;

import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lazy per-{@link PackType} compact startup index for {@code VanillaPackResources}.
 * Preserves multi-root precedence (earlier root wins), uses sorted path slices with parallel
 * {@link Path} arrays (zero per-directory array duplication), and releases completely after startup.
 */
public final class VanillaPackIndex {
    private static final TypeSnapshot FAILED = new TypeSnapshot(Map.of(), false);

    private final TypeSnapshot[] snapshots = new TypeSnapshot[PackType.values().length];
    private final ResourcePathValidationCache validation = new ResourcePathValidationCache();

    public TypeSnapshot getIfLoaded(PackType type) {
        if (type == null) {
            return null;
        }
        TypeSnapshot snapshot = snapshots[type.ordinal()];
        return snapshot != null && snapshot.valid ? snapshot : null;
    }

    public TypeSnapshot getOrBuild(PackType type, Map<PackType, List<Path>> pathsForType) {
        if (type == null || pathsForType == null) {
            return null;
        }
        int slot = type.ordinal();
        TypeSnapshot current = snapshots[slot];
        if (current != null) {
            return current.valid ? current : null;
        }
        synchronized (this) {
            current = snapshots[slot];
            if (current == null) {
                current = build(pathsForType.get(type));
                snapshots[slot] = current;
            }
            return current.valid ? current : null;
        }
    }

    public boolean isValidPath(String path) {
        return validation.error(path).isEmpty();
    }

    public void clear() {
        Arrays.fill(snapshots, null);
    }

    private static TypeSnapshot build(List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            return new TypeSnapshot(Map.of(), true);
        }
        Map<String, Map<String, Path>> byNamespace = new HashMap<>();
        try {
            for (Path typeRoot : roots) {
                if (typeRoot == null || !Files.isDirectory(typeRoot)) {
                    continue;
                }
                try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(typeRoot)) {
                    for (Path nsDir : namespaces) {
                        if (!Files.isDirectory(nsDir)) {
                            continue;
                        }
                        Path fileName = nsDir.getFileName();
                        if (fileName == null) {
                            continue;
                        }
                        String namespace = fileName.toString();
                        Map<String, Path> files = byNamespace.computeIfAbsent(namespace, ignored -> new HashMap<>(256));
                        String separator = nsDir.getFileSystem().getSeparator();
                        Files.walkFileTree(nsDir, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                                if (ResourcePathRules.includeFile(file, attributes)) {
                                    String rel = nsDir.relativize(file).toString();
                                    if (!"/".equals(separator)) {
                                        rel = rel.replace(separator, "/");
                                    }
                                    // Earlier root in pathsForType takes precedence.
                                    files.putIfAbsent(rel, file);
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                                if (file.equals(nsDir) && exc instanceof NoSuchFileException) {
                                    return FileVisitResult.TERMINATE;
                                }
                                throw exc;
                            }
                        });
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            LogUtils.getLogger().debug("VanillaPackIndex fell back to native lookup: {}", exception.toString());
            return FAILED;
        }

        if (byNamespace.isEmpty()) {
            return new TypeSnapshot(Map.of(), true);
        }
        Map<String, NamespaceEntry> built = new HashMap<>(Math.max(4, byNamespace.size() * 2));
        byNamespace.forEach((namespace, map) -> {
            if (map.isEmpty()) {
                return;
            }
            String[] sortedPaths = map.keySet().toArray(String[]::new);
            Arrays.sort(sortedPaths);
            Path[] resolved = new Path[sortedPaths.length];
            for (int i = 0; i < sortedPaths.length; i++) {
                resolved[i] = map.get(sortedPaths[i]);
            }
            built.put(namespace, new NamespaceEntry(ResourcePathIndex.fromPrevalidatedSorted(sortedPaths), resolved));
        });
        return new TypeSnapshot(Map.copyOf(built), true);
    }

    public static final class TypeSnapshot {
        private final Map<String, NamespaceEntry> byNamespace;
        private final boolean valid;

        private TypeSnapshot(Map<String, NamespaceEntry> byNamespace, boolean valid) {
            this.byNamespace = byNamespace;
            this.valid = valid;
        }

        public boolean contains(String namespace, String relativePath) {
            NamespaceEntry entry = byNamespace.get(namespace);
            return entry != null && entry.index.contains(relativePath);
        }

        public IoSupplier<InputStream> getResource(String namespace, String relativePath) {
            NamespaceEntry entry = byNamespace.get(namespace);
            if (entry == null) {
                return null;
            }
            int pos = entry.index.indexOf(relativePath);
            return pos < 0 ? null : IoSupplier.create(entry.files[pos]);
        }

        public boolean hasAnyUnder(String namespace, String path) {
            NamespaceEntry entry = byNamespace.get(namespace);
            return entry != null && entry.index.hasAtOrUnder(path);
        }

        public void listResources(String namespace, String path, PackResources.ResourceOutput output) {
            NamespaceEntry entry = byNamespace.get(namespace);
            if (entry == null) {
                return;
            }
            String[] allPaths = entry.index.rawSortedPaths();
            if (allPaths.length == 0) {
                return;
            }
            String trimmed = path;
            while (!trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == '/') {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (trimmed.isEmpty()) {
                for (int i = 0; i < allPaths.length; i++) {
                    ResourceOutputEmitter.emit(namespace, allPaths[i], IoSupplier.create(entry.files[i]), output);
                }
                return;
            }
            if (path.charAt(path.length() - 1) != '/') {
                int exact = entry.index.indexOf(trimmed);
                if (exact >= 0) {
                    ResourceOutputEmitter.emit(namespace, allPaths[exact], IoSupplier.create(entry.files[exact]), output);
                }
            }
            long range = entry.index.rangeUnder(trimmed);
            if (range >= 0L) {
                int start = (int) (range >>> 32);
                int end = (int) range;
                for (int i = start; i < end; i++) {
                    ResourceOutputEmitter.emit(namespace, allPaths[i], IoSupplier.create(entry.files[i]), output);
                }
            }
        }
    }

    private record NamespaceEntry(ResourcePathIndex index, Path[] files) {
    }
}
