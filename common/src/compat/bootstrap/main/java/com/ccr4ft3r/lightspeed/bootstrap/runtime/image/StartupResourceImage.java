package com.ccr4ft3r.lightspeed.bootstrap.runtime.image;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.JarIndexSnapshot;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.scan.ScanMetadataCache;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.transform.TransformedClassCache;

import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

public final class StartupResourceImage {
    private static final boolean RAW_CLASS_ENABLED =
            Boolean.parseBoolean(System.getProperty("lightspeed.rawClassImage", "true"));
    private static final String RUNTIME_FINGERPRINT = fingerprint("lightspeed-segmented-startup-image-v1", true);
    private static final Path CACHE_DIRECTORY = cacheDirectory();
    private static final StartupByteImage RESOURCES = image(
            "resource-image-v3.bin", "lightspeed.resourceImageMiB", 512, 64, 1024,
            "Lightspeed-Resource-Image-Load", fingerprint("lightspeed-neutral-resource-image-v3", false),
            RUNTIME_FINGERPRINT);
    private static final StartupByteImage CLASSES = image(
            "class-image-v2.bin", "lightspeed.classImageMiB", classImageDefaultMiB(), 64, 512,
            "Lightspeed-Class-Image-Load", RUNTIME_FINGERPRINT, null);
    private static final StartupByteImage SCANS = image(
            "scan-image-v2.bin", "lightspeed.scanImageMiB", 128, 16, 512,
            "Lightspeed-Scan-Image-Load", fingerprint("lightspeed-neutral-scan-image-v2", false),
            RUNTIME_FINGERPRINT);
    private static final StartupByteImage JAR_INDEXES = new StartupByteImage(
            CACHE_DIRECTORY.resolve("jar-index-image-v1.bin"),
            fingerprint("lightspeed-neutral-jar-index-v1", false), 128L * 1024 * 1024,
            "Lightspeed-Jar-Index-Load");
    private static final Set<String> ACTIVE_INDEX_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private StartupResourceImage() {
    }

    public static void startLoading() {
        RESOURCES.start();
        if (RAW_CLASS_ENABLED) {
            CLASSES.start();
        }
        SCANS.start();
        JAR_INDEXES.start();
    }

    public static byte[] transformedClass(String key) {
        StartupByteImage image = LegacyTransformedImage.INSTANCE;
        return image == null ? null : image.get(key);
    }

    public static void recordTransformedClass(String key, byte[] bytes) {
        StartupByteImage image = LegacyTransformedImage.INSTANCE;
        if (image != null) image.record(key, bytes);
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
        byte[] bytes = CLASSES.get(classKey(reference, name));
        return bytes == null ? null : bytes.clone();
    }

    public static void recordRawClass(ModuleReference reference, String name, byte[] bytes) {
        if (RAW_CLASS_ENABLED && reference != null && name != null && bytes != null) {
            CLASSES.record(classKey(reference, name), bytes.clone());
        }
    }

    public static byte[] scanMetadata(String key) {
        return SCANS.get(key);
    }

    public static void recordScanMetadata(String key, byte[] bytes) {
        SCANS.record(key, bytes);
    }

    public static byte[] jarIndex(String key) {
        ACTIVE_INDEX_KEYS.add(key);
        return JAR_INDEXES.get(key);
    }

    public static void recordJarIndex(String key, byte[] bytes) {
        ACTIVE_INDEX_KEYS.add(key);
        JAR_INDEXES.record(key, bytes);
    }

    public static void invalidateJarIndex(String key) {
        JAR_INDEXES.invalidate(key);
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
        JarIndexSnapshot.flushPending();
        JAR_INDEXES.persist(ACTIVE_INDEX_KEYS::contains);
    }

    public static void persistOnExit() {
        RESOURCES.persist();
        if (RAW_CLASS_ENABLED) {
            CLASSES.persist();
        }
        SCANS.persist();
        JarIndexSnapshot.flushPending();
        JAR_INDEXES.persist();
    }

    public static long hits() {
        return RESOURCES.hits();
    }

    public static long misses() {
        return RESOURCES.misses();
    }

    public static long recordedBytes() {
        return RESOURCES.recordedBytes() + CLASSES.recordedBytes()
                + SCANS.recordedBytes() + JAR_INDEXES.recordedBytes();
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
        return RESOURCES.failures() + CLASSES.failures()
                + SCANS.failures() + JAR_INDEXES.failures() + JarIndexSnapshot.failures();
    }

    // Retained for the disconnected legacy cache API; ordinary startup never initializes it.
    private static final class LegacyTransformedImage {
        private static final StartupByteImage INSTANCE = create();

        private static StartupByteImage create() {
            String fingerprint = TransformedClassCache.environmentFingerprint();
            return fingerprint == null ? null : image(
                    "transformed-class-image-v1.bin", "lightspeed.transformedClassImageMiB", 256, 64, 512,
                    "Lightspeed-Transformed-Class-Image-Load", fingerprint, null);
        }
    }

    private static StartupByteImage image(String name, String property, int defaultMiB, int minimumMiB,
                                          int maximumMiB, String threadName, String fingerprint,
                                          String legacyFingerprint) {
        int configured = Integer.getInteger(property, defaultMiB);
        long maxBytes = Math.max(minimumMiB, Math.min(maximumMiB, configured)) * 1024L * 1024L;
        return new StartupByteImage(CACHE_DIRECTORY.resolve(name), fingerprint, maxBytes, threadName,
                legacyFingerprint);
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

    private static String fingerprint(String format, boolean runtimeSensitive) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, format);
            if (runtimeSensitive) update(digest, System.getProperty("java.version", ""));
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
