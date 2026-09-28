package com.ccr4ft3r.lightspeed.bootstrap.runtime.image;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.scan.ScanMetadataCache;

import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

public final class StartupResourceImage {
    private static final boolean RAW_CLASS_ENABLED =
            Boolean.parseBoolean(System.getProperty("lightspeed.rawClassImage", "false"));
    private static final String SEGMENTED_FINGERPRINT = fingerprint();
    private static final Path CACHE_DIRECTORY = cacheDirectory();
    private static final StartupByteImage RESOURCES = image(
            "resource-image-v3.bin", "lightspeed.resourceImageMiB", 512, 64, 1024,
            "Lightspeed-Resource-Image-Load", SEGMENTED_FINGERPRINT);
    private static final StartupByteImage CLASSES = image(
            "class-image-v2.bin", "lightspeed.classImageMiB", classImageDefaultMiB(), 64, 512,
            "Lightspeed-Class-Image-Load", SEGMENTED_FINGERPRINT);
    private static final StartupByteImage SCANS = image(
            "scan-image-v2.bin", "lightspeed.scanImageMiB", 128, 16, 512,
            "Lightspeed-Scan-Image-Load", SEGMENTED_FINGERPRINT);

    private StartupResourceImage() {
    }

    public static void startLoading() {
        RESOURCES.start();
        if (RAW_CLASS_ENABLED) {
            CLASSES.start();
        }
        SCANS.start();
    }

    public static byte[] resource(String key) {
        return RESOURCES.get(key);
    }

    public static void recordResource(String key, byte[] bytes) {
        RESOURCES.record(key, bytes);
    }

    public static byte[] rawClass(ModuleReference reference, String name) {
        if (!RAW_CLASS_ENABLED || reference == null || name == null) {
            return null;
        }
        return CLASSES.get(classKey(reference, name));
    }

    public static void recordRawClass(ModuleReference reference, String name, byte[] bytes) {
        if (RAW_CLASS_ENABLED && reference != null && name != null) {
            CLASSES.record(classKey(reference, name), bytes);
        }
    }

    public static byte[] scanMetadata(String key) {
        return SCANS.get(key);
    }

    public static void recordScanMetadata(String key, byte[] bytes) {
        SCANS.record(key, bytes);
    }

    public static boolean persistScanMetadata(Set<String> keys) {
        long before = SCANS.failures();
        SCANS.persist(keys::contains);
        return SCANS.failures() == before;
    }

    public static void persist() {
        Set<String> sources = ResourceMembershipIndex.activeSourceIdentities();
        RESOURCES.persist(key -> belongsToSource(key, sources));
        if (RAW_CLASS_ENABLED) {
            CLASSES.persist(key -> belongsToSource(key, sources));
        }
        Set<String> scanKeys = ScanMetadataCache.activeKeys();
        SCANS.persist(scanKeys::contains);
    }

    public static long hits() {
        return RESOURCES.hits();
    }

    public static long misses() {
        return RESOURCES.misses();
    }

    public static long recordedBytes() {
        return RESOURCES.recordedBytes() + CLASSES.recordedBytes() + SCANS.recordedBytes();
    }

    public static long classHits() {
        return CLASSES.hits();
    }

    public static long classMisses() {
        return CLASSES.misses();
    }

    public static long classRecordedBytes() {
        return CLASSES.recordedBytes();
    }

    public static long scanHits() {
        return SCANS.hits();
    }

    public static long scanMisses() {
        return SCANS.misses();
    }

    public static long failures() {
        return RESOURCES.failures() + CLASSES.failures() + SCANS.failures();
    }

    private static StartupByteImage image(String name, String property, int defaultMiB, int minimumMiB,
                                          int maximumMiB, String threadName, String fingerprint) {
        int configured = Integer.getInteger(property, defaultMiB);
        long maxBytes = Math.max(minimumMiB, Math.min(maximumMiB, configured)) * 1024L * 1024L;
        return new StartupByteImage(CACHE_DIRECTORY.resolve(name), fingerprint, maxBytes, threadName);
    }

    private static Path cacheDirectory() {
        String configured = System.getProperty("lightspeed.bootstrapCacheDir");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir", "."), "lightspeed-cache", "bootstrap")
                : Path.of(configured);
    }

    private static int classImageDefaultMiB() {
        long maximumHeapMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        return (int) Math.max(64L, Math.min(384L, maximumHeapMiB / 32L));
    }

    private static String classKey(ModuleReference reference, String name) {
        String source = ResourceMembershipIndex.sourceIdentity(reference);
        return source == null ? null : source + '\0' + reference.descriptor().name() + '\0' + name;
    }

    private static String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "lightspeed-segmented-startup-image-v1");
            update(digest, System.getProperty("java.version", ""));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | RuntimeException exception) {
            return "unavailable";
        }
    }

    private static boolean belongsToSource(String key, Set<String> sources) {
        int separator = key.indexOf('\0');
        return separator > 0 && sources.contains(key.substring(0, separator));
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
