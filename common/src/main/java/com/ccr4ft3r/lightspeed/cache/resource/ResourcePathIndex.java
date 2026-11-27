package com.ccr4ft3r.lightspeed.cache.resource;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public final class ResourcePathIndex {
    private static final ResourcePathIndex EMPTY = new ResourcePathIndex(new String[0]);
    private static final int LINEAR_THRESHOLD = 4;

    private final String[] paths;
    private volatile DirRanges dirRanges;

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

    public int indexOf(String path) {
        return Arrays.binarySearch(paths, path);
    }

    public boolean contains(String path) {
        return Arrays.binarySearch(paths, path) >= 0;
    }

    public List<String> entries() {
        return List.of(paths.clone());
    }

    /** O(1) check whether any entry equals {@code path} or lies directly/transitively under {@code path/}. */
    public boolean hasAtOrUnder(String path) {
        if (paths.length == 0) {
            return false;
        }
        String dir = trimTrailingSlashes(path);
        if (dir.isEmpty()) {
            return true;
        }
        if (paths.length <= LINEAR_THRESHOLD) {
            for (String entry : paths) {
                if (matchesAtOrUnder(entry, dir)) {
                    return true;
                }
            }
            return false;
        }
        DirRanges index = dirRanges();
        if (index.ranges.getLong(dir) >= 0L) {
            return true;
        }
        int slash = dir.lastIndexOf('/');
        if (slash < 0) {
            return index.hasTopLevelFiles && Arrays.binarySearch(paths, dir) >= 0;
        }
        if (index.ranges.getLong(dir.substring(0, slash)) < 0L) {
            return false;
        }
        return Arrays.binarySearch(paths, dir) >= 0;
    }

    /** Packed {@code (start << 32) | (end & 0xFFFFFFFFL)} slice for entries under {@code directory/}, or {@code -1L}. */
    public long rangeUnder(String directory) {
        if (paths.length == 0) {
            return -1L;
        }
        String dir = trimTrailingSlashes(directory);
        if (dir.isEmpty()) {
            return (long) paths.length;
        }
        if (paths.length <= LINEAR_THRESHOLD) {
            int start = -1;
            int end = 0;
            int dirLen = dir.length();
            for (int i = 0; i < paths.length; i++) {
                String entry = paths[i];
                if (entry.length() > dirLen && entry.charAt(dirLen) == '/' && entry.startsWith(dir)) {
                    if (start < 0) {
                        start = i;
                    }
                    end = i + 1;
                } else if (start >= 0) {
                    break;
                }
            }
            return start < 0 ? -1L : (((long) start) << 32) | (end & 0xFFFFFFFFL);
        }
        return dirRanges().ranges.getLong(dir);
    }

    public List<String> entriesUnder(String directory) {
        long range = rangeUnder(directory);
        if (range < 0L) {
            return List.of();
        }
        int start = (int) (range >>> 32);
        int end = (int) range;
        return List.of(Arrays.copyOfRange(paths, start, end));
    }

    public void forEachUnder(String directory, Consumer<String> consumer) {
        long range = rangeUnder(directory);
        if (range < 0L) {
            return;
        }
        int start = (int) (range >>> 32);
        int end = (int) range;
        for (int index = start; index < end; index++) {
            consumer.accept(paths[index]);
        }
    }

    /** Path-backed packs can enumerate a file itself as well as descendants of a directory. */
    public void forEachAtOrUnder(String path, Consumer<String> consumer) {
        if (paths.length == 0) {
            return;
        }
        String dir = trimTrailingSlashes(path);
        if (dir.isEmpty()) {
            for (String entry : paths) {
                consumer.accept(entry);
            }
            return;
        }
        if (paths.length <= LINEAR_THRESHOLD) {
            for (String entry : paths) {
                if (matchesAtOrUnder(entry, dir)) {
                    consumer.accept(entry);
                }
            }
            return;
        }
        DirRanges index = dirRanges();
        boolean checkExact = path.charAt(path.length() - 1) != '/';
        if (checkExact) {
            int slash = dir.lastIndexOf('/');
            boolean mayExist = slash < 0
                    ? index.hasTopLevelFiles
                    : index.ranges.getLong(dir.substring(0, slash)) >= 0L;
            if (mayExist && Arrays.binarySearch(paths, dir) >= 0) {
                consumer.accept(dir);
            }
        }
        long range = index.ranges.getLong(dir);
        if (range >= 0L) {
            int start = (int) (range >>> 32);
            int end = (int) range;
            for (int i = start; i < end; i++) {
                consumer.accept(paths[i]);
            }
        }
    }

    private DirRanges dirRanges() {
        DirRanges current = dirRanges;
        if (current != null) {
            return current;
        }
        DirRanges built = buildDirRanges(paths);
        dirRanges = built;
        return built;
    }

    private static DirRanges buildDirRanges(String[] sortedPaths) {
        Object2LongOpenHashMap<String> ranges = new Object2LongOpenHashMap<>(Math.max(8, sortedPaths.length >>> 2));
        ranges.defaultReturnValue(-1L);
        String[] stackDirs = new String[16];
        int[] stackSlashes = new int[16];
        int[] stackStarts = new int[16];
        int depth = 0;
        boolean hasTopLevelFiles = false;

        for (int i = 0; i < sortedPaths.length; i++) {
            String entry = sortedPaths[i];
            int entryLen = entry.length();
            int keep = 0;
            while (keep < depth) {
                int slashPos = stackSlashes[keep];
                if (entryLen > slashPos && entry.charAt(slashPos) == '/'
                        && entry.regionMatches(0, stackDirs[keep], 0, slashPos)) {
                    keep++;
                } else {
                    break;
                }
            }
            for (int k = depth - 1; k >= keep; k--) {
                ranges.put(stackDirs[k], (((long) stackStarts[k]) << 32) | (i & 0xFFFFFFFFL));
                stackDirs[k] = null;
            }
            depth = keep;

            int searchFrom = depth == 0 ? 0 : stackSlashes[depth - 1] + 1;
            int slash = entry.indexOf('/', searchFrom);
            if (depth == 0 && slash < 0) {
                hasTopLevelFiles = true;
            }
            while (slash >= 0) {
                if (depth == stackDirs.length) {
                    int next = depth << 1;
                    stackDirs = Arrays.copyOf(stackDirs, next);
                    stackSlashes = Arrays.copyOf(stackSlashes, next);
                    stackStarts = Arrays.copyOf(stackStarts, next);
                }
                stackDirs[depth] = entry.substring(0, slash);
                stackSlashes[depth] = slash;
                stackStarts[depth] = i;
                depth++;
                slash = entry.indexOf('/', slash + 1);
            }
        }
        for (int k = depth - 1; k >= 0; k--) {
            ranges.put(stackDirs[k], (((long) stackStarts[k]) << 32) | (sortedPaths.length & 0xFFFFFFFFL));
        }
        return new DirRanges(ranges, hasTopLevelFiles);
    }

    private static boolean matchesAtOrUnder(String entry, String dir) {
        int dirLen = dir.length();
        if (entry.length() == dirLen) {
            return entry.equals(dir);
        }
        return entry.length() > dirLen && entry.charAt(dirLen) == '/' && entry.startsWith(dir);
    }

    private static String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    private record DirRanges(Object2LongOpenHashMap<String> ranges, boolean hasTopLevelFiles) {
    }
}
