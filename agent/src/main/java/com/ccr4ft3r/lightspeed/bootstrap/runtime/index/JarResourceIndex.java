package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.jar.JarFile;

final class JarResourceIndex {
    private static final JarResourceIndex UNSUPPORTED = new JarResourceIndex(null, null);
    private final String[] entries;
    private final Bloom bloom;

    private JarResourceIndex(String[] entries, Bloom bloom) {
        this.entries = entries;
        this.bloom = bloom;
    }

    static JarResourceIndex build(ResourceMembershipIndex.RootRegistration registration) {
        if (registration.paths().length == 0) {
            return UNSUPPORTED;
        }
        TreeSet<String> names = new TreeSet<>();
        for (Path path : registration.paths()) {
            if (!isPhysicalJar(path) || !addJarEntries(path, registration.filter(), names)) {
                return UNSUPPORTED;
            }
        }
        String[] entries = names.toArray(String[]::new);
        return new JarResourceIndex(entries, Bloom.create(entries));
    }

    static JarResourceIndex unsupported() {
        return UNSUPPORTED;
    }

    boolean isExact() {
        return entries != null;
    }

    int size() {
        return entries == null ? 0 : entries.length;
    }

    boolean contains(String name) {
        return isExact() && bloom.mightContain(name) && Arrays.binarySearch(entries, name) >= 0;
    }

    JarResourceView view(String rootPrefix, String imageSource) {
        if (!isExact()) {
            return null;
        }
        String prefix = rootPrefix.isEmpty() ? "" : rootPrefix + '/';
        int start = lowerBound(entries, prefix);
        int end = start;
        while (end < entries.length && entries[end].startsWith(prefix)) {
            end++;
        }
        String[] relative = new String[end - start];
        for (int index = start; index < end; index++) {
            relative[index - start] = entries[index].substring(prefix.length());
        }
        return new JarResourceView(relative, Bloom.create(relative), imageSource, rootPrefix);
    }

    private static boolean addJarEntries(Path path, BiPredicate<String, String> filter, Set<String> names) {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.isMultiRelease()) {
                return false;
            }
            String base = ResourceMembershipIndex.normalize(path.toString());
            jar.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> {
                String name = ResourceMembershipIndex.normalize(entry.getName());
                if (filter == null || filter.test(name, base)) {
                    names.add(name);
                }
            });
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean isPhysicalJar(Path path) {
        return path != null
                && path.getFileSystem() == FileSystems.getDefault()
                && path.toString().endsWith(".jar")
                && Files.isRegularFile(path);
    }

    private static int lowerBound(String[] values, String target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle].compareTo(target) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    record Bloom(BitSet bits, int mask) {
        private static final int HASH_COUNT = 4;

        static Bloom create(String[] entries) {
            int requested = Math.max(1 << 10, entries.length * 16);
            int bitCount = Integer.highestOneBit(Math.min(requested - 1, (1 << 27) - 1)) << 1;
            if (bitCount <= 0 || bitCount > 1 << 27) {
                bitCount = 1 << 27;
            }
            Bloom bloom = new Bloom(new BitSet(bitCount), bitCount - 1);
            Arrays.stream(entries).forEach(bloom::add);
            return bloom;
        }

        boolean mightContain(String name) {
            long first = hash(name);
            long second = mix(first ^ ((long) name.length() << 32));
            for (int index = 0; index < HASH_COUNT; index++) {
                if (!bits.get((int) (first + index * second) & mask)) {
                    return false;
                }
            }
            return true;
        }

        private void add(String name) {
            long first = hash(name);
            long second = mix(first ^ ((long) name.length() << 32));
            for (int index = 0; index < HASH_COUNT; index++) {
                bits.set((int) (first + index * second) & mask);
            }
        }

        private static long hash(String value) {
            long hash = 0xcbf29ce484222325L;
            for (int index = 0; index < value.length(); index++) {
                hash = (hash ^ value.charAt(index)) * 0x100000001b3L;
            }
            return mix(hash);
        }

        private static long mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53L;
            return value ^ value >>> 33;
        }
    }
}
