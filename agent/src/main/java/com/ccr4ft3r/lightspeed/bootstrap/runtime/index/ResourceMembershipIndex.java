package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

public final class ResourceMembershipIndex {
    private static final ConcurrentHashMap<Path, CompletableFuture<Membership>> INDEXES = new ConcurrentHashMap<>();
    private static final LongAdder QUERIES = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder INDEXED_ENTRIES = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder QUALIFICATION_CHECKS = new LongAdder();

    private ResourceMembershipIndex() {
    }

    public static boolean mightContain(Path root, Path primary, String name) {
        QUERIES.increment();
        if (name == null || root == null) {
            return true;
        }

        CompletableFuture<Membership> created = new CompletableFuture<>();
        CompletableFuture<Membership> future = INDEXES.putIfAbsent(root, created);
        if (future == null) {
            future = created;
            try {
                created.complete(buildMembership(root, primary));
            } catch (IOException | RuntimeException exception) {
                FAILURES.increment();
                created.complete(Membership.ALWAYS_MAYBE);
            }
        }

        boolean result = future.join().mightContain(name);
        if (!result) {
            REJECTED.increment();
        }
        return result;
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

    private static Membership buildMembership(Path root, Path primary) throws IOException {
        if (!isPhysicalJar(primary) || !isSupportedRoot(root)) {
            return Membership.ALWAYS_MAYBE;
        }
        List<String> entries = new ArrayList<>();
        boolean multiRelease = false;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = normalize(root.relativize(path));
                if (name.startsWith("META-INF/versions/")) {
                    multiRelease = true;
                }
                entries.add(name);
            }
        }
        INDEXED_ENTRIES.add(entries.size());
        return multiRelease ? Membership.ALWAYS_MAYBE : BloomMembership.create(entries);
    }

    private static boolean isPhysicalJar(Path path) {
        QUALIFICATION_CHECKS.increment();
        if (path == null) {
            return false;
        }
        FileSystem fileSystem = path.getFileSystem();
        if (!"file".equalsIgnoreCase(fileSystem.provider().getScheme())
                || !path.toString().endsWith(".jar")
                || !Files.isRegularFile(path)) {
            return false;
        }
        try {
            return Files.size(path) > 0;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean isSupportedRoot(Path root) {
        if (root == null) {
            return false;
        }
        String scheme = root.getFileSystem().provider().getScheme();
        return "union".equalsIgnoreCase(scheme) || "jar".equalsIgnoreCase(scheme);
    }

    private static String normalize(Path path) {
        String name = path.toString().replace('\\', '/');
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private interface Membership {
        Membership ALWAYS_MAYBE = ignored -> true;

        boolean mightContain(String name);
    }

    private record BloomMembership(BitSet bits, int mask) implements Membership {
        private static final int HASH_COUNT = 4;
        private static final int MIN_BITS = 1 << 10;
        private static final int MAX_BITS = 1 << 27;

        static BloomMembership create(List<String> entries) {
            int requested = Math.max(MIN_BITS, entries.size() * 16);
            int bitCount = Integer.highestOneBit(Math.min(requested - 1, MAX_BITS - 1)) << 1;
            if (bitCount <= 0 || bitCount > MAX_BITS) {
                bitCount = MAX_BITS;
            }
            BitSet bits = new BitSet(bitCount);
            BloomMembership membership = new BloomMembership(bits, bitCount - 1);
            entries.forEach(membership::add);
            return membership;
        }

        @Override
        public boolean mightContain(String name) {
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
                hash ^= value.charAt(index);
                hash *= 0x100000001b3L;
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
