package com.ccr4ft3r.lightspeed.cache.resource;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ResourcePathIndex {
    private static final ResourcePathIndex EMPTY = new ResourcePathIndex(new String[0]);

    private final String[] paths;

    private ResourcePathIndex(String[] paths) {
        this.paths = paths;
    }

    public static ResourcePathIndex from(Collection<String> source) {
        if (source.isEmpty()) {
            return EMPTY;
        }
        String[] copy = new String[source.size()];
        int count = 0;
        for (String entry : source) {
            if (entry != null) {
                copy[count++] = entry;
            }
        }
        if (count == 0) {
            return EMPTY;
        }
        if (count == 1) {
            return new ResourcePathIndex(copy.length == 1 ? copy : new String[]{copy[0]});
        }
        Arrays.sort(copy, 0, count);
        int unique = 1;
        String prev = copy[0];
        for (int i = 1; i < count; i++) {
            String current = copy[i];
            if (!current.equals(prev)) {
                copy[unique++] = current;
                prev = current;
            }
        }
        String[] paths = unique == copy.length ? copy : Arrays.copyOf(copy, unique);
        return new ResourcePathIndex(paths);
    }

    public static ResourcePathIndex fromSortedDistinct(List<String> source) {
        if (source.isEmpty()) {
            return EMPTY;
        }
        String previous = null;
        for (String path : source) {
            if (path == null || previous != null && previous.compareTo(path) >= 0) {
                return from(source);
            }
            previous = path;
        }
        return new ResourcePathIndex(source.toArray(String[]::new));
    }

    public static ResourcePathIndex fromPrevalidatedSorted(String[] sortedDistinctPaths) {
        return sortedDistinctPaths == null || sortedDistinctPaths.length == 0
                ? EMPTY
                : new ResourcePathIndex(sortedDistinctPaths);
    }

    public String[] rawSortedPaths() {
        return paths;
    }

    public int size() {
        return paths.length;
    }

    public boolean contains(String path) {
        return Arrays.binarySearch(paths, path) >= 0;
    }

    public List<String> entries() {
        return List.of(paths.clone());
    }

    public List<String> entriesUnder(String directory) {
        int start = firstUnder(directory);
        if (start == paths.length) {
            return List.of();
        }
        String prefix = prefix(directory);
        int end = endOfPrefix(start, prefix);
        return List.of(Arrays.copyOfRange(paths, start, end));
    }

    public void forEachUnder(String directory, Consumer<String> consumer) {
        String prefix = prefix(directory);
        for (int index = lowerBound(prefix); index < paths.length && paths[index].startsWith(prefix); index++) {
            consumer.accept(paths[index]);
        }
    }

    /** Path-backed packs can enumerate a file itself as well as descendants of a directory. */
    public void forEachAtOrUnder(String path, Consumer<String> consumer) {
        if (!path.isEmpty() && contains(path)) {
            consumer.accept(path);
        }
        forEachUnder(path, consumer);
    }

    private int firstUnder(String directory) {
        return lowerBound(prefix(directory));
    }

    private int endOfPrefix(int start, String prefix) {
        int index = start;
        while (index < paths.length && paths[index].startsWith(prefix)) {
            index++;
        }
        return index;
    }

    private int lowerBound(String value) {
        int low = 0;
        int high = paths.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (paths[middle].compareTo(value) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static String prefix(String directory) {
        if (directory.isEmpty()) {
            return "";
        }
        return directory.charAt(directory.length() - 1) == '/' ? directory : directory + '/';
    }
}
