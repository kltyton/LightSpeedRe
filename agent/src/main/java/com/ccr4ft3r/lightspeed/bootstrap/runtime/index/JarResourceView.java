package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class JarResourceView {
    private final String[] entries;
    private final JarResourceIndex.Bloom bloom;
    private final Map<String, DirectoryIndex> resourcesByNamespace;
    private final Map<String, Set<String>> namespacesByDirectory;
    private final String imagePrefix;

    JarResourceView(String[] entries, JarResourceIndex.Bloom bloom, String imageSource, String rootPrefix) {
        this.entries = entries;
        this.bloom = bloom;
        this.resourcesByNamespace = buildNamespaceIndexes(entries);
        this.namespacesByDirectory = buildNamespaces(resourcesByNamespace.keySet());
        this.imagePrefix = imageSource == null ? null : imageSource + '\0' + rootPrefix + '\0';
    }

    boolean contains(String name) {
        return bloom.mightContain(name) && Arrays.binarySearch(entries, name) >= 0;
    }

    List<String> entries(String basePrefix, String requestedPath) {
        DirectoryIndex index = resourcesByNamespace.get(basePrefix);
        return index == null ? List.of() : index.entries(requestedPath);
    }

    Set<String> namespaces(String directory) {
        return namespacesByDirectory.getOrDefault(directory, Set.of());
    }

    String imageKey(String name) {
        return imagePrefix == null ? null : imagePrefix + name;
    }

    private static Map<String, DirectoryIndex> buildNamespaceIndexes(String[] entries) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String entry : entries) {
            int first = entry.indexOf('/');
            int second = first < 0 ? -1 : entry.indexOf('/', first + 1);
            if (second < 0) {
                continue;
            }
            String directory = entry.substring(0, first);
            if (!directory.equals("assets") && !directory.equals("data")) {
                continue;
            }
            String base = entry.substring(0, second);
            grouped.computeIfAbsent(base, ignored -> new ArrayList<>()).add(entry.substring(second + 1));
        }
        Map<String, DirectoryIndex> indexes = new HashMap<>(grouped.size() * 2);
        grouped.forEach((base, values) -> indexes.put(base, new DirectoryIndex(values.toArray(String[]::new))));
        indexes.put("", new DirectoryIndex(entries));
        return Map.copyOf(indexes);
    }

    private static Map<String, Set<String>> buildNamespaces(Set<String> bases) {
        Map<String, Set<String>> mutable = new HashMap<>();
        for (String base : bases) {
            int separator = base.indexOf('/');
            if (separator > 0 && separator < base.length() - 1) {
                mutable.computeIfAbsent(base.substring(0, separator), ignored -> new LinkedHashSet<>())
                        .add(base.substring(separator + 1));
            }
        }
        Map<String, Set<String>> immutable = new HashMap<>(mutable.size() * 2);
        mutable.forEach((directory, namespaces) -> immutable.put(directory, Set.copyOf(namespaces)));
        return Map.copyOf(immutable);
    }

    private static final class DirectoryIndex {
        private final Map<String, List<String>> ranges;

        private DirectoryIndex(String[] entries) {
            List<String> values = Collections.unmodifiableList(Arrays.asList(entries));
            Map<String, MutableRange> mutable = new HashMap<>();
            mutable.put("", new MutableRange(0, entries.length));
            for (int index = 0; index < entries.length; index++) {
                String entry = entries[index];
                for (int separator = entry.indexOf('/'); separator >= 0;
                     separator = entry.indexOf('/', separator + 1)) {
                    String directory = entry.substring(0, separator);
                    MutableRange range = mutable.get(directory);
                    if (range == null) {
                        mutable.put(directory, new MutableRange(index, index + 1));
                    } else {
                        range.end = index + 1;
                    }
                }
            }
            Map<String, List<String>> built = new HashMap<>(mutable.size() * 2);
            mutable.forEach((directory, range) -> built.put(directory, values.subList(range.start, range.end)));
            this.ranges = Map.copyOf(built);
        }

        private List<String> entries(String requestedPath) {
            return ranges.getOrDefault(requestedPath, List.of());
        }
    }

    private static final class MutableRange {
        private final int start;
        private int end;

        private MutableRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
