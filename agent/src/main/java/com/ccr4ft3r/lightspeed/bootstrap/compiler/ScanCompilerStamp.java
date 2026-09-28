package com.ccr4ft3r.lightspeed.bootstrap.compiler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ScanCompilerStamp {
    private static final int MAGIC = 0x4c535043;
    private static final int VERSION = 1;
    private static final int MAX_FILES = 10_000;
    private static final int MAX_CLASSPATH = 128;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final String FILE_NAME = "scan-pack-compiler-v1.stamp";

    private ScanCompilerStamp() {
    }

    static Optional<Stats> current(Path cacheDirectory, List<Path> jars) {
        Path file = cacheDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)
                || !Files.isRegularFile(cacheDirectory.resolve("scan-image-v2.bin.current"))) {
            return Optional.empty();
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION
                    || !readString(input).equals(Runtime.version().toString())) {
                return Optional.empty();
            }
            if (!readIdentities(input, classpathIdentities()) || !readIdentities(input, identities(jars))) {
                return Optional.empty();
            }
            int supported = input.readInt();
            int skipped = input.readInt();
            if (supported < 0 || skipped < 0 || supported + skipped != jars.size() || input.read() != -1) {
                return Optional.empty();
            }
            return Optional.of(new Stats(supported, skipped));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    static void write(Path cacheDirectory, List<Path> jars, int supported, int skipped) throws IOException {
        List<Identity> classpath = classpathIdentities();
        List<Identity> mods = identities(jars);
        Path target = cacheDirectory.resolve(FILE_NAME);
        Path temporary = Files.createTempFile(cacheDirectory, FILE_NAME, ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                writeString(output, Runtime.version().toString());
                writeIdentities(output, classpath, MAX_CLASSPATH);
                writeIdentities(output, mods, MAX_FILES);
                output.writeInt(supported);
                output.writeInt(skipped);
            }
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    temporary, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<Identity> classpathIdentities() throws IOException {
        String[] entries = System.getProperty("java.class.path", "").split(
                java.util.regex.Pattern.quote(java.io.File.pathSeparator));
        if (entries.length > MAX_CLASSPATH) {
            throw new IOException("compiler classpath exceeds stamp bounds");
        }
        List<Path> paths = new ArrayList<>(entries.length);
        for (String entry : entries) {
            if (!entry.isBlank()) {
                paths.add(Path.of(entry));
            }
        }
        return identities(paths);
    }

    private static List<Identity> identities(List<Path> paths) throws IOException {
        List<Identity> identities = new ArrayList<>(paths.size());
        for (Path path : paths) {
            Path normalized = path.toAbsolutePath().normalize();
            boolean exists = Files.exists(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            identities.add(new Identity(normalized.toString(), exists ? Files.size(normalized) : -1L,
                    exists ? Files.getLastModifiedTime(normalized).toMillis() : -1L));
        }
        return List.copyOf(identities);
    }

    private static void writeIdentities(DataOutputStream output, List<Identity> identities, int maximum)
            throws IOException {
        if (identities.size() > maximum) {
            throw new IOException("compiler stamp identity count exceeds bounds");
        }
        output.writeInt(identities.size());
        for (Identity identity : identities) {
            writeString(output, identity.path());
            output.writeLong(identity.size());
            output.writeLong(identity.modified());
        }
    }

    private static boolean readIdentities(DataInputStream input, List<Identity> expected) throws IOException {
        int count = input.readInt();
        if (count != expected.size() || count < 0 || count > MAX_FILES) {
            return false;
        }
        for (Identity identity : expected) {
            if (!readString(input).equals(identity.path())
                    || input.readLong() != identity.size() || input.readLong() != identity.modified()) {
                return false;
            }
        }
        return true;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("compiler stamp string exceeds bounds");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("compiler stamp string exceeds bounds");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("compiler stamp is truncated");
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("compiler stamp UTF-8 is invalid");
        }
        return value;
    }

    record Stats(int supported, int skipped) {
    }

    private record Identity(String path, long size, long modified) {
    }
}
