package com.ccr4ft3r.lightspeed.bootstrap.runtime.image;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;

final class PackImageFile {
    private static final int MAGIC = 0x4c535032;
    private static final int VERSION = 2;
    private static final int HEADER_BYTES = Integer.BYTES * 4 + Long.BYTES * 2;
    private static final int MAX_FINGERPRINT_BYTES = 4096;
    private static final int MAX_KEY_BYTES = 64 * 1024;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final long MAX_INDEX_BYTES = 128L * 1024L * 1024L;
    private static final String GENERATION_SUFFIX = ".lspi";
    private static final String POINTER_SUFFIX = ".current";

    private final Path logicalFile;
    private final String fingerprint;
    private final long maxDataBytes;
    private final int maxEntryBytes;

    PackImageFile(Path logicalFile, String fingerprint, long maxDataBytes, int maxEntryBytes) {
        this.logicalFile = logicalFile;
        this.fingerprint = fingerprint;
        this.maxDataBytes = maxDataBytes;
        this.maxEntryBytes = maxEntryBytes;
    }

    Image load() throws IOException {
        Pointer pointer = readPointer();
        if (pointer == null) {
            return Image.empty();
        }
        Path generation = logicalFile.getParent().resolve(pointer.fileName()).normalize();
        if (!generation.getParent().equals(logicalFile.getParent()) || !Files.isRegularFile(generation)) {
            throw new IOException("invalid PackImage generation path");
        }
        cleanupGenerations(generation);
        return mapGeneration(generation);
    }

    Image write(Map<String, byte[]> entries) throws IOException {
        if (entries.size() > MAX_ENTRIES) {
            throw new IOException("too many PackImage entries " + entries.size());
        }
        Files.createDirectories(logicalFile.getParent());
        Path temporary = Files.createTempFile(logicalFile.getParent(), logicalFile.getFileName().toString(), ".tmp");
        try {
            writeGeneration(temporary, entries);
            validateHeader(temporary);
            String digest = sha256(temporary);
            String generationName = logicalFile.getFileName() + "." + digest + GENERATION_SUFFIX;
            Path generation = logicalFile.resolveSibling(generationName);
            if (Files.isRegularFile(generation)) {
                if (!digest.equals(sha256(generation))) {
                    generationName = logicalFile.getFileName() + "." + temporary.getFileName()
                            + "." + digest + GENERATION_SUFFIX;
                    generation = logicalFile.resolveSibling(generationName);
                    move(temporary, generation);
                } else {
                    Files.deleteIfExists(temporary);
                }
            } else {
                move(temporary, generation);
            }
            writePointer(new Pointer(generationName, digest));
            return Image.heap(entries);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeGeneration(Path target, Map<String, byte[]> source) throws IOException {
        TreeMap<String, byte[]> entries = new TreeMap<>(source);
        byte[] fingerprintBytes = fingerprint.getBytes(StandardCharsets.UTF_8);
        if (fingerprintBytes.length == 0 || fingerprintBytes.length > MAX_FINGERPRINT_BYTES) {
            throw new IOException("invalid PackImage fingerprint length " + fingerprintBytes.length);
        }

        long dataBytes = 0;
        long tableBytes = 0;
        Map<String, byte[]> keys = new HashMap<>(entries.size() * 2);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] value = entry.getValue();
            if (key.length == 0 || key.length > MAX_KEY_BYTES || value == null
                    || value.length == 0 || value.length > maxEntryBytes) {
                throw new IOException("invalid PackImage entry " + entry.getKey());
            }
            keys.put(entry.getKey(), key);
            tableBytes = Math.addExact(tableBytes,
                    Integer.BYTES + key.length + Long.BYTES + Integer.BYTES + Integer.BYTES);
            dataBytes = Math.addExact(dataBytes, value.length);
        }
        if (tableBytes > MAX_INDEX_BYTES || dataBytes > maxDataBytes) {
            throw new IOException("PackImage exceeds configured bounds");
        }
        long dataStart = Math.addExact(HEADER_BYTES + fingerprintBytes.length, tableBytes);

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(fingerprintBytes.length);
            output.writeInt(entries.size());
            output.writeLong(dataStart);
            output.writeLong(dataBytes);
            output.write(fingerprintBytes);
            long offset = dataStart;
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                byte[] key = keys.get(entry.getKey());
                byte[] value = entry.getValue();
                output.writeInt(key.length);
                output.write(key);
                output.writeLong(offset);
                output.writeInt(value.length);
                output.writeInt(crc32(value));
                offset += value.length;
            }
            for (byte[] value : entries.values()) {
                output.write(value);
            }
        }
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private Image mapGeneration(Path generation) throws IOException {
        long fileBytes = Files.size(generation);
        if (fileBytes < HEADER_BYTES || fileBytes > maxDataBytes + MAX_INDEX_BYTES
                || fileBytes > Integer.MAX_VALUE) {
            throw new IOException("invalid PackImage file size " + fileBytes);
        }
        try (FileChannel channel = FileChannel.open(generation, StandardOpenOption.READ)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileBytes);
            return parse(mapped, fileBytes);
        }
    }

    private Image parse(ByteBuffer input, long fileBytes) throws IOException {
        try {
            if (input.getInt() != MAGIC || input.getInt() != VERSION) {
                throw new IOException("unsupported PackImage header");
            }
            int fingerprintLength = input.getInt();
            int count = input.getInt();
            long dataStart = input.getLong();
            long dataBytes = input.getLong();
            if (fingerprintLength <= 0 || fingerprintLength > MAX_FINGERPRINT_BYTES
                    || count < 0 || count > MAX_ENTRIES || dataBytes < 0 || dataBytes > maxDataBytes
                    || dataStart < HEADER_BYTES || dataStart > fileBytes
                    || dataStart - HEADER_BYTES > MAX_INDEX_BYTES
                    || dataStart + dataBytes != fileBytes) {
                throw new IOException("invalid PackImage bounds");
            }
            byte[] fingerprintBytes = readBytes(input, fingerprintLength);
            if (!fingerprint.equals(strictUtf8(fingerprintBytes))) {
                throw new IOException("PackImage fingerprint mismatch");
            }

            Map<String, Slice> slices = new HashMap<>(Math.max(16, count * 2));
            long expectedOffset = dataStart;
            for (int index = 0; index < count; index++) {
                int keyLength = input.getInt();
                if (keyLength <= 0 || keyLength > MAX_KEY_BYTES) {
                    throw new IOException("invalid PackImage key length " + keyLength);
                }
                String key = strictUtf8(readBytes(input, keyLength));
                long offset = input.getLong();
                int length = input.getInt();
                int checksum = input.getInt();
                if (offset != expectedOffset || length <= 0 || length > maxEntryBytes
                        || offset + length > fileBytes || slices.putIfAbsent(
                        key, new Slice(Math.toIntExact(offset), length, checksum)) != null) {
                    throw new IOException("invalid PackImage slice " + key);
                }
                expectedOffset += length;
            }
            if (input.position() != dataStart || expectedOffset != fileBytes) {
                throw new IOException("PackImage table and data region disagree");
            }
            return Image.mapped(input.asReadOnlyBuffer(), Map.copyOf(slices), dataBytes);
        } catch (ArithmeticException | java.nio.BufferUnderflowException | java.nio.BufferOverflowException exception) {
            throw new IOException("truncated PackImage", exception);
        }
    }

    private void validateHeader(Path path) throws IOException {
        long fileBytes = Files.size(path);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("written PackImage header mismatch");
            }
            int fingerprintLength = input.readInt();
            int count = input.readInt();
            long dataStart = input.readLong();
            long dataBytes = input.readLong();
            if (fingerprintLength <= 0 || fingerprintLength > MAX_FINGERPRINT_BYTES
                    || count < 0 || count > MAX_ENTRIES || dataStart + dataBytes != fileBytes) {
                throw new IOException("written PackImage bounds mismatch");
            }
            if (!fingerprint.equals(strictUtf8(input.readNBytes(fingerprintLength)))) {
                throw new IOException("written PackImage fingerprint mismatch");
            }
            long expectedOffset = dataStart;
            for (int index = 0; index < count; index++) {
                int keyLength = input.readInt();
                if (keyLength <= 0 || keyLength > MAX_KEY_BYTES
                        || input.readNBytes(keyLength).length != keyLength) {
                    throw new IOException("written PackImage key is truncated");
                }
                long offset = input.readLong();
                int length = input.readInt();
                input.readInt();
                if (offset != expectedOffset || length <= 0 || length > maxEntryBytes) {
                    throw new IOException("written PackImage slice mismatch");
                }
                expectedOffset += length;
            }
            if (HEADER_BYTES + fingerprintLength + tableBytes(fileBytes, dataBytes) != dataStart
                    || expectedOffset != fileBytes) {
                throw new IOException("written PackImage layout mismatch");
            }
        }
    }

    private long tableBytes(long fileBytes, long dataBytes) {
        return fileBytes - dataBytes - HEADER_BYTES - fingerprint.getBytes(StandardCharsets.UTF_8).length;
    }

    private Pointer readPointer() throws IOException {
        Path pointer = pointerFile();
        if (!Files.isRegularFile(pointer)) {
            return null;
        }
        var lines = Files.readAllLines(pointer, StandardCharsets.US_ASCII);
        if (lines.size() != 2 || lines.get(0).length() > 512 || !lines.get(1).matches("[0-9a-f]{64}")) {
            throw new IOException("invalid PackImage pointer");
        }
        String expectedPrefix = logicalFile.getFileName() + ".";
        String expectedSuffix = "." + lines.get(1) + GENERATION_SUFFIX;
        String fileName = lines.get(0);
        if (!fileName.startsWith(expectedPrefix) || !fileName.endsWith(expectedSuffix)
                || !Path.of(fileName).getFileName().toString().equals(fileName)) {
            throw new IOException("invalid PackImage generation name");
        }
        return new Pointer(fileName, lines.get(1));
    }

    private void writePointer(Pointer pointer) throws IOException {
        Path target = pointerFile();
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, pointer.fileName() + '\n' + pointer.digest() + '\n',
                    StandardCharsets.US_ASCII);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void cleanupGenerations(Path keep) {
        String glob = logicalFile.getFileName() + ".*" + GENERATION_SUFFIX;
        try (DirectoryStream<Path> generations = Files.newDirectoryStream(logicalFile.getParent(), glob)) {
            for (Path generation : generations) {
                if (!generation.equals(keep)) {
                    Files.deleteIfExists(generation);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private Path pointerFile() {
        return logicalFile.resolveSibling(logicalFile.getFileName() + POINTER_SUFFIX);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (var input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    private static byte[] readBytes(ByteBuffer input, int length) throws IOException {
        if (length < 0 || input.remaining() < length) {
            throw new IOException("truncated PackImage field");
        }
        byte[] bytes = new byte[length];
        input.get(bytes);
        return bytes;
    }

    private static String strictUtf8(byte[] bytes) throws IOException {
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("invalid PackImage UTF-8");
        }
        return value;
    }

    private record Pointer(String fileName, String digest) {
    }

    private record Slice(int offset, int length, int checksum) {
    }

    static final class Image {
        private static final Image EMPTY = new Image(null, Map.of(), Map.of(), 0);
        private final ByteBuffer mapped;
        private final Map<String, Slice> slices;
        private final Map<String, byte[]> heap;
        private final long dataBytes;

        private Image(ByteBuffer mapped, Map<String, Slice> slices, Map<String, byte[]> heap, long dataBytes) {
            this.mapped = mapped;
            this.slices = slices;
            this.heap = heap;
            this.dataBytes = dataBytes;
        }

        static Image empty() {
            return EMPTY;
        }

        static Image mapped(ByteBuffer mapped, Map<String, Slice> slices, long dataBytes) {
            return new Image(mapped, slices, Map.of(), dataBytes);
        }

        static Image heap(Map<String, byte[]> entries) {
            long bytes = entries.values().stream().mapToLong(value -> value.length).sum();
            return new Image(null, Map.of(), Map.copyOf(entries), bytes);
        }

        byte[] get(String key) throws IOException {
            byte[] value = heap.get(key);
            if (value != null || mapped == null) {
                return value;
            }
            Slice slice = slices.get(key);
            if (slice == null) {
                return null;
            }
            ByteBuffer view = mapped.duplicate();
            view.position(slice.offset()).limit(slice.offset() + slice.length());
            byte[] bytes = new byte[slice.length()];
            view.get(bytes);
            if (crc32(bytes) != slice.checksum()) {
                throw new IOException("PackImage entry checksum mismatch " + key);
            }
            return bytes;
        }

        boolean containsKey(String key) {
            return heap.containsKey(key) || slices.containsKey(key);
        }

        int size() {
            return heap.isEmpty() ? slices.size() : heap.size();
        }

        long dataBytes() {
            return dataBytes;
        }

        long entryBytes(String key) {
            byte[] value = heap.get(key);
            if (value != null) {
                return value.length;
            }
            Slice slice = slices.get(key);
            return slice == null ? 0 : slice.length();
        }

        boolean isMapped() {
            return mapped != null;
        }

        long segmentBytes(String segment) {
            String prefix = segment + '\0';
            if (!heap.isEmpty()) {
                return heap.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                        .mapToLong(entry -> entry.getValue().length).sum();
            }
            return slices.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                    .mapToLong(entry -> entry.getValue().length()).sum();
        }

        void forEach(BiConsumer<String, byte[]> consumer) throws IOException {
            if (!heap.isEmpty()) {
                heap.forEach(consumer);
                return;
            }
            for (String key : slices.keySet()) {
                consumer.accept(key, get(key));
            }
        }

        void forEachKey(java.util.function.Consumer<String> consumer) {
            (heap.isEmpty() ? slices.keySet() : heap.keySet()).forEach(consumer);
        }
    }
}
