package com.ccr4ft3r.lightspeed.bootstrap.runtime.image;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;

public final class StartupByteImageSegmentCheck {
    private StartupByteImageSegmentCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("lightspeed-segmented-image-");
        Path file = directory.resolve("image.bin");
        String firstSource = directory.resolve("first.jar") + "\told";
        String changedSource = directory.resolve("first.jar") + "\tnew";
        String unchangedSource = directory.resolve("second.jar") + "\tstable";
        byte[] oldBytes = new byte[]{1, 2, 3, 4};
        byte[] newBytes = new byte[]{5, 6, 7, 8};
        byte[] unchangedBytes = new byte[]{9, 10, 11, 12};
        try {
            StartupByteImage initial = new StartupByteImage(file, "test", 8, "initial-load");
            initial.record(firstSource + '\0' + "entry", oldBytes);
            initial.record(unchangedSource + '\0' + "entry", unchangedBytes);
            initial.persist();

            StartupByteImage changed = new StartupByteImage(file, "test", 8, "changed-load");
            require(MessageDigest.isEqual(unchangedBytes, changed.get(unchangedSource + '\0' + "entry")),
                    "an unchanged source segment was not reused");
            require(changed.get(changedSource + '\0' + "entry") == null,
                    "a changed source unexpectedly reused stale bytes");
            changed.record(changedSource + '\0' + "entry", newBytes);
            changed.persist(key -> key.startsWith(changedSource + '\0')
                    || key.startsWith(unchangedSource + '\0'));
            require(changed.pendingBytes() == 0,
                    "persisted segment bytes remained dirty and would be rewritten at shutdown");
            changed.persist(key -> key.startsWith(changedSource + '\0')
                    || key.startsWith(unchangedSource + '\0'));

            StartupByteImage reloaded = new StartupByteImage(file, "test", 8, "reloaded-image");
            require(MessageDigest.isEqual(newBytes, reloaded.get(changedSource + '\0' + "entry")),
                    "a changed source could not replace its stale segment at the image size limit");
            require(MessageDigest.isEqual(unchangedBytes, reloaded.get(unchangedSource + '\0' + "entry")),
                    "replacing one source discarded an unrelated segment");
            PackImageFile.Image mapped = new PackImageFile(file, "test", 8, 32 * 1024 * 1024).load();
            require(mapped.isMapped(), "persisted PackImage was not memory mapped");
            try (var generations = Files.list(directory)) {
                require(generations.filter(path -> path.getFileName().toString().endsWith(".lspi")).count() == 1,
                        "obsolete PackImage generations were not removed before mapping");
            }

            Path pointer = file.resolveSibling(file.getFileName() + ".current");
            byte[] validPointer = Files.readAllBytes(pointer);
            Files.writeString(pointer, "../outside.lspi\nnot-a-digest\n");
            StartupByteImage invalidPointer = new StartupByteImage(file, "test", 8, "invalid-pointer");
            require(invalidPointer.get(changedSource + '\0' + "entry") == null,
                    "invalid PackImage pointer did not fail open");
            require(invalidPointer.failures() > 0, "invalid PackImage pointer was not reported");
            Files.write(pointer, validPointer);

            Path corruptFile = directory.resolve("corrupt.bin");
            String corruptSource = directory.resolve("corrupt.jar") + "\tstable";
            String corruptKey = corruptSource + '\0' + "entry";
            StartupByteImage corruptWriter = new StartupByteImage(
                    corruptFile, "test", 8, "corrupt-writer");
            corruptWriter.record(corruptKey, unchangedBytes);
            corruptWriter.persist();
            Path corruptPointer = corruptFile.resolveSibling(corruptFile.getFileName() + ".current");
            String generationName = Files.readAllLines(corruptPointer).get(0);
            Path generation = directory.resolve(generationName);
            byte[] generationBytes = Files.readAllBytes(generation);
            generationBytes[generationBytes.length - 1] ^= 0x5a;
            Files.write(generation, generationBytes);
            StartupByteImage corruptEntry = new StartupByteImage(corruptFile, "test", 8, "corrupt-entry");
            require(corruptEntry.get(corruptKey) == null,
                    "corrupt PackImage entry did not fail open");
            require(corruptEntry.failures() > 0, "corrupt PackImage entry was not reported");
            System.out.println("SEGMENTED_STARTUP_IMAGE_OK");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
