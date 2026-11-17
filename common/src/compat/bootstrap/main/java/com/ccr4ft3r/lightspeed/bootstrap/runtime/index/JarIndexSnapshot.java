package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.image.StartupResourceImage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.jar.JarFile;

/** Persists neutral ZIP catalogs and index results while replaying every filter callback. */
public final class JarIndexSnapshot {
    private static final int FORMAT = 1;
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_STRING = 256 * 1024;
    private static final ConcurrentHashMap<String, JarIndexSnapshot> PENDING = new ConcurrentHashMap<>();
    private static final LongAdder RESTORED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();

    private final Entry[] files;
    private final String[] names;
    private final String[] directories;
    private final BitSet accepted;
    private final JarResourceIndex index;

    private JarIndexSnapshot(Entry[] files, String[] names, String[] directories, BitSet accepted, JarResourceIndex index) {
        this.files = files;
        this.names = names;
        this.directories = directories;
        this.accepted = accepted;
        this.index = index;
    }

    static JarResourceIndex resolve(JarFile jar, String key, Path path, long size, long modified,
                                    BiPredicate<String, String> filter) throws IOException {
        JarIndexSnapshot previous = PENDING.get(key);
        if (previous == null) {
            byte[] bytes = StartupResourceImage.jarIndex(key);
            if (bytes != null) {
                try {
                    previous = decode(bytes, path.toAbsolutePath().normalize());
                } catch (IOException exception) {
                    FAILURES.increment();
                    StartupResourceImage.invalidateJarIndex(key);
                    System.err.println("[Lightspeed] Cannot restore JAR index " + path + ": " + exception);
                }
            }
        }
        JarIndexSnapshot catalog = previous == null ? collect(jar) : previous;
        BitSet selection = catalog.select(filter, ResourceMembershipIndex.normalize(path.toString()));
        if (previous != null && selection.equals(previous.accepted)) {
            RESTORED.increment();
            return previous.index;
        }
        if (catalog.files == null) {
            JarIndexSnapshot collected = collect(jar);
            if (catalog.names.length != collected.files.length || !java.util.Arrays.equals(catalog.directories, collected.directories)) {
                throw new IOException("JAR index catalog does not match the open archive");
            }
            for (int i = 0; i < catalog.names.length; i++) {
                if (!catalog.names[i].equals(collected.files[i].name)) {
                    throw new IOException("JAR index catalog does not match the open archive");
                }
            }
            catalog = collected;
        }
        JarResourceIndex rebuilt = catalog.build(path, size, modified, selection);
        if (rebuilt.isExact()) {
            PENDING.put(key, new JarIndexSnapshot(catalog.files, catalog.names, catalog.directories, selection, rebuilt));
        }
        return rebuilt;
    }

    private static JarIndexSnapshot collect(JarFile jar) {
        ArrayList<Entry> files = new ArrayList<>();
        Set<String> directories = new HashSet<>();
        directories.add("");
        jar.stream().forEach(entry -> {
            String name = ResourceMembershipIndex.normalize(entry.getName());
            JarResourceIndex.addDirectories(directories, name);
            if (!entry.isDirectory()) {
                files.add(new Entry(name, entry.getCrc(), entry.getSize(), entry.getCompressedSize(), entry.getMethod()));
            }
        });
        Entry[] entries = files.toArray(Entry[]::new);
        return new JarIndexSnapshot(entries, null, directories.stream().sorted().toArray(String[]::new), null, null);
    }

    private BitSet select(BiPredicate<String, String> filter, String base) {
        int fileCount = files == null ? names.length : files.length;
        BitSet selection = new BitSet(fileCount + directories.length);
        if (filter == null) {
            selection.set(0, fileCount + directories.length);
            return selection;
        }
        for (int i = 0; i < fileCount; i++) {
            String name = files == null ? names[i] : files[i].name;
            if (filter.test(name, base)) selection.set(i);
        }
        for (int i = 0; i < directories.length; i++) {
            String name = directories[i];
            if (filter.test(name.isEmpty() ? "/" : name, base)) selection.set(fileCount + i);
        }
        return selection;
    }

    private JarResourceIndex build(Path path, long size, long modified, BitSet selection) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        Path normalized = path.toAbsolutePath().normalize();
        update(digest, normalized.toString());
        update(digest, size + "\t" + modified);
        TreeSet<String> names = new TreeSet<>();
        for (int i = 0; i < files.length; i++) {
            if (selection.get(i)) {
                Entry entry = files[i];
                names.add(entry.name);
                update(digest, entry.name);
                update(digest, entry.crc + "\t" + entry.size + "\t" + entry.compressed + "\t" + entry.method);
            }
        }
        Set<String> selectedDirectories = selectedDirectories(selection);
        for (String directory : directories) {
            if (selectedDirectories.contains(directory)) update(digest, "directory\0" + directory);
        }
        if (!selectedDirectories.contains("")) return JarResourceIndex.unsupported();
        return JarResourceIndex.create(names, selectedDirectories, selectedDirectories.size() != directories.length,
                normalized + "\t" + HexFormat.of().formatHex(digest.digest()), true);
    }

    private Set<String> selectedDirectories(BitSet selection) {
        HashSet<String> selected = new HashSet<>();
        for (int i = 0; i < directories.length; i++) {
            if (selection.get((files == null ? names.length : files.length) + i)) selected.add(directories[i]);
        }
        return selected;
    }

    public static synchronized void flushPending() {
        PENDING.forEach((key, snapshot) -> {
            try {
                byte[] bytes = snapshot.encode();
                StartupResourceImage.invalidateJarIndex(key);
                StartupResourceImage.recordJarIndex(key, bytes);
            } catch (IOException exception) {
                FAILURES.increment();
                System.err.println("[Lightspeed] Cannot persist JAR index: " + exception);
            }
            PENDING.remove(key, snapshot);
        });
    }

    public static long restored() { return RESTORED.sum(); }
    public static long failures() { return FAILURES.sum(); }

    private byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            private int size;
            @Override public void write(int value) throws IOException {
                if (size == MAX_BYTES) throw new IOException("JAR index snapshot exceeds byte limit");
                bytes.write(value); size++;
            }
            @Override public void write(byte[] data, int offset, int count) throws IOException {
                if (count > MAX_BYTES - size) throw new IOException("JAR index snapshot exceeds byte limit");
                bytes.write(data, offset, count); size += count;
            }
        };
        try (DataOutputStream output = new DataOutputStream(bounded)) {
            output.writeInt(FORMAT);
            output.writeInt(files.length);
            String[] snapshotNames = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                Entry entry = files[i];
                snapshotNames[i] = entry.name;
                writeString(output, entry.name);
                output.writeLong(entry.crc); output.writeLong(entry.size); output.writeLong(entry.compressed);
                output.writeInt(entry.method);
            }
            output.writeInt(directories.length);
            for (String directory : directories) writeString(output, directory);
            byte[] selection = accepted.toByteArray();
            output.writeInt(selection.length); output.write(selection);
            index.writeSnapshot(output, snapshotNames, accepted);
        }
        return bytes.toByteArray();
    }

    private static JarIndexSnapshot decode(byte[] bytes, Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != FORMAT) throw new IOException("Unsupported JAR index snapshot format");
            int count = readCount(input, 32);
            String[] names = new String[count];
            for (int i = 0; i < count; i++) {
                names[i] = readString(input);
                if (!names[i].equals(ResourceMembershipIndex.normalize(names[i]))) throw new IOException("Non-normalized ZIP entry");
                input.skipNBytes(3 * Long.BYTES + Integer.BYTES);
            }
            int directoryCount = readCount(input, 4);
            String[] directories = new String[directoryCount];
            for (int i = 0; i < directoryCount; i++) {
                directories[i] = readString(input);
                if (!directories[i].equals(ResourceMembershipIndex.normalize(directories[i]))) throw new IOException("Non-normalized ZIP directory");
                if (i == 0 ? !directories[i].isEmpty()
                        : directories[i - 1].compareTo(directories[i]) >= 0 || !directories[i].endsWith("/")) {
                    throw new IOException("Invalid ZIP directory order");
                }
            }
            if (directoryCount == 0) throw new IOException("ZIP root directory is missing");
            int length = input.readInt();
            if (length < 0 || length > (count + directoryCount + 7) / 8 || length > input.available()) throw new IOException("Invalid ZIP selection size");
            BitSet selection = BitSet.valueOf(input.readNBytes(length));
            if (selection.length() > count + directoryCount) throw new IOException("ZIP selection outside catalog");
            HashSet<String> selectedDirectories = new HashSet<>();
            for (int i = 0; i < directoryCount; i++) {
                if (selection.get(count + i)) selectedDirectories.add(directories[i]);
            }
            if (!selectedDirectories.contains("")) throw new IOException("ZIP selection excludes root");
            JarResourceIndex index = JarResourceIndex.readSnapshot(input, path, names, selection,
                    selectedDirectories, selectedDirectories.size() != directoryCount);
            if (input.available() != 0) throw new IOException("Trailing JAR index data");
            return new JarIndexSnapshot(null, names, directories, selection, index);
        }
    }

    static int readCount(DataInputStream input, int minimumBytes) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES || count > input.available() / minimumBytes) throw new IOException("Invalid JAR index count");
        return count;
    }

    static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING || length > input.available()) throw new IOException("Invalid JAR index string");
        byte[] bytes = input.readNBytes(length);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw new IOException("Invalid JAR index UTF-8");
        return value;
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING) throw new IOException("JAR index string exceeds limit");
        output.writeInt(bytes.length); output.write(bytes);
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }

    private record Entry(String name, long crc, long size, long compressed, int method) { }
}
