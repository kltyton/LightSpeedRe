package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.jar.JarFile;

final class JarResourceIndex {
    private static final JarResourceIndex UNSUPPORTED = new JarResourceIndex(null, null, null, Set.of(), -1);
    private final String[] entries;
    private final Bloom bloom;
    private final String sourceIdentity;
    private final Set<String> packages;
    private final int classEntries;

    private JarResourceIndex(String[] entries, Bloom bloom, String sourceIdentity, Set<String> packages,
                             int classEntries) {
        this.entries = entries;
        this.bloom = bloom;
        this.sourceIdentity = sourceIdentity;
        this.packages = packages;
        this.classEntries = classEntries;
    }

    static JarResourceIndex build(ResourceMembershipIndex.RootRegistration registration) {
        if (registration.paths().length == 0) {
            return UNSUPPORTED;
        }
        TreeSet<String> names = new TreeSet<>();
        MessageDigest digest = sha256();
        for (Path path : registration.paths()) {
            if (!isPhysicalJar(path)) {
                return UNSUPPORTED;
            }
            Path normalized = path.toAbsolutePath().normalize();
            update(digest, normalized.toString());
            try {
                update(digest, Files.size(normalized) + "\t" + Files.getLastModifiedTime(normalized).toMillis());
            } catch (IOException exception) {
                return UNSUPPORTED;
            }
            if (!addJarEntries(path, registration.filter(), names, digest)) {
                return UNSUPPORTED;
            }
        }
        String[] entries = names.toArray(String[]::new);
        String identity = registration.paths()[0].toAbsolutePath().normalize() + "\t"
                + HexFormat.of().formatHex(digest.digest());
        Set<String> packages = new HashSet<>();
        int classEntries = 0;
        for (String entry : entries) {
            if (!entry.endsWith(".class")) {
                continue;
            }
            classEntries++;
            int separator = entry.lastIndexOf('/');
            if (separator > 0 && !entry.startsWith("META-INF/")) {
                packages.add(entry.substring(0, separator).replace('/', '.'));
            }
        }
        return new JarResourceIndex(entries, Bloom.create(entries), identity, Set.copyOf(packages), classEntries);
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

    String sourceIdentity() {
        return sourceIdentity;
    }

    int classEntryCount() {
        return classEntries;
    }

    Set<String> packages() {
        return packages;
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

    private static boolean addJarEntries(Path path, BiPredicate<String, String> filter, Set<String> names,
                                         MessageDigest digest) {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.isMultiRelease()) {
                return false;
            }
            String base = ResourceMembershipIndex.normalize(path.toString());
            jar.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> {
                String name = ResourceMembershipIndex.normalize(entry.getName());
                if (filter == null || filter.test(name, base)) {
                    names.add(name);
                    update(digest, name);
                    update(digest, entry.getCrc() + "\t" + entry.getSize() + "\t"
                            + entry.getCompressedSize() + "\t" + entry.getMethod());
                }
            });
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
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
