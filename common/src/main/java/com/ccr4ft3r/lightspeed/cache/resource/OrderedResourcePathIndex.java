package com.ccr4ft3r.lightspeed.cache.resource;

import it.unimi.dsi.fastutil.ints.IntArrays;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Selects path ranges through sorted ordinals and emits them in their original order, including duplicates. */
public final class OrderedResourcePathIndex {
    private final List<String> original;
    private final int[] sorted;

    private OrderedResourcePathIndex(List<String> paths) {
        original = List.copyOf(paths);
        sorted = new int[paths.size()];
        Arrays.setAll(sorted, index -> index);
        IntArrays.quickSort(sorted, (left, right) -> original.get(left).compareTo(original.get(right)));
    }

    public static OrderedResourcePathIndex from(List<String> paths) {
        return new OrderedResourcePathIndex(paths);
    }

    public boolean contains(String path) {
        int first = lowerBound(path);
        return first < sorted.length && original.get(sorted[first]).equals(path);
    }

    public List<String> entriesStartingWith(String prefix) {
        int start = lowerBound(prefix);
        int end = endOfPrefix(start, prefix);
        if (start == 0 && end == sorted.length) {
            return original;
        }
        return originalOrder(Arrays.copyOfRange(sorted, start, end));
    }

    public void forEachAtOrUnder(String path, Consumer<String> consumer) {
        String prefix = path.isEmpty() || path.endsWith("/") ? path : path + "/";
        int start = lowerBound(prefix);
        int end = endOfPrefix(start, prefix);
        if (path.isEmpty()) {
            original.forEach(consumer);
            return;
        }
        int exact = lowerBound(path);
        int exactEnd = exact;
        if (!path.endsWith("/")) {
            while (exactEnd < sorted.length && original.get(sorted[exactEnd]).equals(path)) {
                exactEnd++;
            }
        }
        int[] selected = new int[end - start + exactEnd - exact];
        System.arraycopy(sorted, start, selected, 0, end - start);
        System.arraycopy(sorted, exact, selected, end - start, exactEnd - exact);
        originalOrder(selected).forEach(consumer);
    }

    private List<String> originalOrder(int[] ordinals) {
        Arrays.sort(ordinals);
        return new AbstractList<>() {
            @Override public String get(int index) { return original.get(ordinals[index]); }
            @Override public int size() { return ordinals.length; }
        };
    }

    private int endOfPrefix(int start, String prefix) {
        int end = start;
        while (end < sorted.length && original.get(sorted[end]).startsWith(prefix)) {
            end++;
        }
        return end;
    }

    private int lowerBound(String value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (original.get(sorted[middle]).compareTo(value) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
