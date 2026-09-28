package com.ccr4ft3r.lightspeed.cache.assets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class SnapshotFaultIsolationCheck {
    private SnapshotFaultIsolationCheck() {
    }

    public static void main(String[] arguments) throws Exception {
        require(!SnapshotMode.nativeImagesEnabled(null), "native image snapshots must remain disabled by default");
        require(SnapshotMode.nativeImagesEnabled("true"), "explicit true must enable native image snapshots");
        require(!SnapshotMode.nativeImagesEnabled("false"), "explicit false must disable native image snapshots");
        require(!SnapshotMode.modelInputsEnabled(null), "model input snapshots must remain disabled by default");
        require(SnapshotMode.modelInputsEnabled("TRUE"), "explicit true must enable model snapshots");
        require(!SnapshotMode.modelInputsEnabled("false"), "explicit false must disable model snapshots");
        require(SnapshotMode.shaderProgramsEnabled(null), "content-addressed shader snapshots must be enabled by default");
        require(SnapshotMode.shaderProgramsEnabled("TRUE"), "explicit true must enable shader snapshots");
        require(!SnapshotMode.shaderProgramsEnabled("false"), "explicit false must disable shader snapshots");
        Path directory = Files.createTempDirectory("lightspeed-snapshot-check");
        Path modelFile = directory.resolve("models.bin");
        Path imageFile = directory.resolve("images.bin");
        byte[] model = {1, 2, 3};
        byte[] image = {4, 5, 6, 7};

        SnapshotFileStore models = store(modelFile, 0x4c534d44);
        SnapshotFileStore images = store(imageFile, 0x4c53494d);
        require(models.put("model", model), "model snapshot was rejected");
        require(images.put("image", image), "image snapshot was rejected");
        models.persist();
        images.persist();

        Files.write(modelFile, new byte[]{0, 1, 2, 3});
        SnapshotFileStore corruptedModels = store(modelFile, 0x4c534d44);
        SnapshotFileStore intactImages = store(imageFile, 0x4c53494d);
        require(corruptedModels.get("model") == null, "corrupt model domain was accepted");
        require(corruptedModels.failures() > 0, "corrupt model domain did not report failure");
        require(Arrays.equals(image, intactImages.get("image")),
                "one corrupt domain invalidated an independent snapshot");
        require(intactImages.failures() == 0, "intact image domain reported failure");
        System.out.println("SNAPSHOT_FAULT_ISOLATION_OK");
    }

    private static SnapshotFileStore store(Path file, int magic) {
        return new SnapshotFileStore(file, magic, 1, 16, 1024, 4096);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
