package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
import java.util.jar.Manifest;

final class JarResourceIndex {
    private static final JarResourceIndex UNSUPPORTED = new JarResourceIndex(null, null, null, Set.of(), -1, Set.of(), false, false);
    private final String[] entries;
    private final Bloom bloom;
    private final String sourceIdentity;
    private final Set<String> packages;
    private final int classEntries;
    private final Set<String> directories;
    private final boolean filteredDirectories;
    private final boolean indexedListing;

    private JarResourceIndex(String[] entries, Bloom bloom, String sourceIdentity, Set<String> packages,
                             int classEntries, Set<String> directories, boolean filteredDirectories, boolean indexedListing) {
        this.entries = entries;
        this.bloom = bloom;
        this.sourceIdentity = sourceIdentity;
        this.packages = packages;
        this.classEntries = classEntries;
        this.directories = directories;
        this.filteredDirectories = filteredDirectories;
        this.indexedListing = indexedListing;
    }

    static JarResourceIndex build(ResourceMembershipIndex.RootRegistration registration) {
        if (registration.paths().length == 0) {
            return UNSUPPORTED;
        }
        if (registration.paths().length == 1 && !isPhysicalJar(registration.paths()[0])) {
            return buildNested(registration);
        }
        if (registration.paths().length == 1) {
            JarResourceIndex restored = singlePhysical(registration);
            if (restored != null) return restored;
        }
        TreeSet<String> names = new TreeSet<>();
        Set<String> directories = new HashSet<>();
        Set<String> allDirectories = new HashSet<>();
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
            if (!addJarEntries(path, registration.filter(), names, directories, allDirectories, digest)) {
                return UNSUPPORTED;
            }
        }
        if (!directories.contains("")) return UNSUPPORTED;
        String identity = registration.paths()[0].toAbsolutePath().normalize() + "\t"
                + HexFormat.of().formatHex(digest.digest());
        return create(names, directories, directories.size() != allDirectories.size(), identity, true);
    }

    private static JarResourceIndex buildNested(ResourceMembershipIndex.RootRegistration registration) {
        try {
            NestedJarSource source = NestedJarSource.resolve(registration.paths()[0]);
            if (source == null) return UNSUPPORTED;
            Path root = registration.root();
            Path manifestPath = root.resolve("META-INF/MANIFEST.MF");
            if (Files.isRegularFile(manifestPath)) {
                try (var input = Files.newInputStream(manifestPath)) {
                    if (Boolean.parseBoolean(new Manifest(input).getMainAttributes().getValue("Multi-Release"))) {
                        return UNSUPPORTED;
                    }
                }
            }
            TreeSet<String> names = new TreeSet<>();
            Set<String> directories = new HashSet<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    String name = ResourceMembershipIndex.normalize(root.relativize(directory).toString());
                    directories.add(name.isEmpty() ? "" : name + '/');
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile()) {
                        throw new IOException("JarJar index requires regular archive entries");
                    }
                    names.add(ResourceMembershipIndex.normalize(root.relativize(file).toString()));
                    return FileVisitResult.CONTINUE;
                }
            });
            MessageDigest digest = sha256();
            update(digest, "jarjar-native-view-v1");
            update(digest, source.parentKey());
            for (String name : names) update(digest, name);
            for (String directory : directories.stream().sorted().toList()) update(digest, "directory\0" + directory);
            return create(names, directories, false,
                    source.locator() + '\t' + HexFormat.of().formatHex(digest.digest()), false);
        } catch (IOException exception) {
            return UNSUPPORTED;
        }
    }

    static JarResourceIndex create(TreeSet<String> names, Set<String> directories,
                                           boolean filteredDirectories, String identity, boolean indexedListing) {
        String[] entries = names.toArray(String[]::new);
        Set<String> packages = new HashSet<>();
        int classEntries = 0;
        for (String entry : entries) {
            if (!entry.endsWith(".class") || filteredDirectories && !reachable(directories, entry, 0)) {
                continue;
            }
            classEntries++;
            int separator = entry.lastIndexOf('/');
            if (separator > 0 && !entry.startsWith("META-INF/")) {
                packages.add(entry.substring(0, separator).replace('/', '.'));
            }
        }
        return new JarResourceIndex(entries, Bloom.create(entries), identity, Set.copyOf(packages), classEntries,
                Set.copyOf(directories), filteredDirectories, indexedListing);
    }

    private static JarResourceIndex singlePhysical(ResourceMembershipIndex.RootRegistration registration) {
        Path path = registration.paths()[0];
        if (!isPhysicalJar(path)) return null;
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            if (manifest != null && Boolean.parseBoolean(manifest.getMainAttributes().getValue("Multi-Release"))) {
                return UNSUPPORTED;
            }
            // A larger catalog cannot fit the bounded snapshot format; retain normal indexing.
            if (jar.size() > 1_000_000) return null;
            Path normalized = path.toAbsolutePath().normalize();
            long size = Files.size(normalized);
            long modified = Files.getLastModifiedTime(normalized).toMillis();
            String key = ZipDirectoryIdentity.key(normalized, size, modified, jar);
            return key == null ? null : JarIndexSnapshot.resolve(jar, key, path, size, modified, registration.filter());
        } catch (IOException | RuntimeException exception) {
            return UNSUPPORTED;
        }
    }

    void writeSnapshot(DataOutputStream output, String[] rawNames, BitSet selected) throws IOException {
        output.write(HexFormat.of().parseHex(sourceIdentity.substring(sourceIdentity.lastIndexOf('\t') + 1)));
        int[] ids = new int[entries.length];
        for (int i = 0; i < rawNames.length; i++) {
            if (!selected.get(i)) continue;
            int position = Arrays.binarySearch(entries, rawNames[i]);
            if (position >= 0) ids[position] = i;
        }
        output.writeInt(ids.length);
        for (int id : ids) output.writeInt(id);
        output.writeInt(classEntries);
        output.writeInt(packages.size());
        for (String name : packages) JarIndexSnapshot.writeString(output, name);
        output.writeInt(bloom.mask);
        long[] words = bloom.bits.toLongArray();
        output.writeInt(words.length);
        for (long word : words) output.writeLong(word);
    }

    static JarResourceIndex readSnapshot(DataInputStream input, Path path, String[] rawNames, BitSet selected,
                                          Set<String> directories, boolean filteredDirectories) throws IOException {
        byte[] digest = input.readNBytes(32);
        if (digest.length != 32) throw new IOException("Truncated JAR index identity");
        int count = JarIndexSnapshot.readCount(input, Integer.BYTES);
        if (count > rawNames.length) throw new IOException("JAR index exceeds raw catalog");
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            int id = input.readInt();
            if (id < 0 || id >= rawNames.length || !selected.get(id)) throw new IOException("Invalid JAR index entry reference");
            names[i] = rawNames[id];
            if (i > 0 && names[i - 1].compareTo(names[i]) >= 0) throw new IOException("Invalid JAR index ordering");
        }
        int classes = input.readInt();
        if (classes < 0 || classes > count) throw new IOException("Invalid JAR class count");
        int packageCount = JarIndexSnapshot.readCount(input, Integer.BYTES);
        Set<String> packages = new HashSet<>();
        for (int i = 0; i < packageCount; i++) {
            if (!packages.add(JarIndexSnapshot.readString(input))) throw new IOException("Duplicate JAR index package");
        }
        int mask = input.readInt();
        int words = JarIndexSnapshot.readCount(input, Long.BYTES);
        if (mask != Bloom.bitCount(count) - 1 || words > (mask + 64L) / 64L) throw new IOException("Invalid JAR index membership mask");
        long[] values = new long[words];
        for (int i = 0; i < words; i++) values[i] = input.readLong();
        BitSet bits = BitSet.valueOf(values);
        if (bits.length() > mask + 1) throw new IOException("JAR membership bits exceed mask");
        return new JarResourceIndex(names, new Bloom(bits, mask), path + "\t" + HexFormat.of().formatHex(digest),
                Set.copyOf(packages), classes, Set.copyOf(directories), filteredDirectories, true);
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

    boolean containsFile(String name) {
        return isExact() && bloom.mightContain(name) && Arrays.binarySearch(entries, name) >= 0;
    }

    boolean contains(String name) {
        if (!isExact()) {
            return false;
        }
        if (name.isEmpty() || containsFile(name)) {
            return true;
        }
        String directory = name.endsWith("/") ? name : name + '/';
        if (directories.contains(directory)) {
            return true;
        }
        int position = lowerBound(entries, directory);
        return position < entries.length && entries[position].startsWith(directory);
    }

    JarResourceView view(String rootPrefix, String imageSource) {
        // Nested archives retain their native filesystem enumeration order.
        if (!isExact() || !indexedListing) {
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
        Set<String> relativeDirectories = new HashSet<>();
        for (String directory : directories) {
            if (directory.startsWith(prefix)) relativeDirectories.add(directory.substring(prefix.length()));
        }
        return new JarResourceView(relative, Bloom.create(relative), Set.copyOf(relativeDirectories),
                filteredDirectories, imageSource, rootPrefix);
    }

    private static boolean addJarEntries(Path path, BiPredicate<String, String> filter, Set<String> names,
                                         Set<String> directories, Set<String> allDirectories, MessageDigest digest) {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            if (manifest != null && Boolean.parseBoolean(manifest.getMainAttributes().getValue("Multi-Release"))) {
                return false;
            }
            String base = ResourceMembershipIndex.normalize(path.toString());
            Set<String> rawDirectories = new HashSet<>();
            rawDirectories.add("");
            jar.stream().forEach(entry -> {
                String name = ResourceMembershipIndex.normalize(entry.getName());
                addDirectories(rawDirectories, name);
                if (entry.isDirectory()) {
                    return;
                }
                if (filter == null || filter.test(name, base)) {
                    names.add(name);
                    update(digest, name);
                    update(digest, entry.getCrc() + "\t" + entry.getSize() + "\t"
                            + entry.getCompressedSize() + "\t" + entry.getMethod());
                }
            });
            allDirectories.addAll(rawDirectories);
            for (String directory : rawDirectories.stream().sorted().toList()) {
                String filterPath = directory.isEmpty() ? "/" : directory;
                if (filter == null || filter.test(filterPath, base)) {
                    directories.add(directory);
                    update(digest, "directory\0" + directory);
                }
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    static void addDirectories(Set<String> directories, String name) {
        for (int separator = name.lastIndexOf('/'); separator >= 0;
             separator = name.lastIndexOf('/', separator - 1)) {
            // Every known directory already has its complete parent chain.
            if (!directories.add(name.substring(0, separator + 1))) break;
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

    static boolean reachable(Set<String> directories, String file, int rootLength) {
        for (int separator = file.lastIndexOf('/'); separator > rootLength;
             separator = file.lastIndexOf('/', separator - 1)) {
            if (!directories.contains(file.substring(0, separator + 1))) return false;
        }
        return true;
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
        private static final ThreadLocal<HashMemo> LAST_HASH = ThreadLocal.withInitial(HashMemo::new);

        static Bloom create(String[] entries) {
            int bitCount = bitCount(entries.length);
            Bloom bloom = new Bloom(new BitSet(bitCount), bitCount - 1);
            Arrays.stream(entries).forEach(bloom::add);
            return bloom;
        }

        static Bloom forDirectories(Set<String> directories) {
            int bitCount = bitCount(directories.size());
            Bloom bloom = new Bloom(new BitSet(bitCount), bitCount - 1);
            for (String directory : directories) {
                int length = directory.endsWith("/") ? directory.length() - 1 : directory.length();
                bloom.add(directory, length);
            }
            return bloom;
        }

        static int bitCount(int count) {
            int requested = Math.max(1 << 10, count * 16);
            int bits = Integer.highestOneBit(Math.min(requested - 1, (1 << 27) - 1)) << 1;
            return bits <= 0 || bits > 1 << 27 ? 1 << 27 : bits;
        }

        boolean mightContain(String name) {
            long first = cachedHash(name);
            long second = mix(first ^ ((long) name.length() << 32));
            for (int index = 0; index < HASH_COUNT; index++) {
                if (!bits.get((int) (first + index * second) & mask)) {
                    return false;
                }
            }
            return true;
        }

        private void add(String name) {
            add(name, name.length());
        }

        private void add(String name, int length) {
            long first = hash(name, length);
            long second = mix(first ^ ((long) length << 32));
            for (int index = 0; index < HASH_COUNT; index++) {
                bits.set((int) (first + index * second) & mask);
            }
        }

        private static long hash(String value) {
            return hash(value, value.length());
        }

        private static long hash(String value, int length) {
            long hash = 0xcbf29ce484222325L;
            for (int index = 0; index < length; index++) {
                hash = (hash ^ value.charAt(index)) * 0x100000001b3L;
            }
            return mix(hash);
        }

        private static long cachedHash(String value) {
            HashMemo memo = LAST_HASH.get();
            if (value.equals(memo.name)) return memo.hash;
            long hash = hash(value);
            memo.name = value;
            memo.hash = hash;
            return hash;
        }

        private static final class HashMemo {
            private String name;
            private long hash;
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
