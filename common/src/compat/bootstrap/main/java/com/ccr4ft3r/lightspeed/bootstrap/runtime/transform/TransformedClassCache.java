package com.ccr4ft3r.lightspeed.bootstrap.runtime.transform;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.image.StartupResourceImage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Caches final ClassTransformer outputs keyed by environment fingerprint plus input byte length and FNV-1a 64 hash,
 * bypassing Mixin, AT, and CoreMod bytecode parsing on warm startups.
 */
public final class TransformedClassCache {
    // Final class bytes cannot be replayed without the transform participants' runtime effects.
    private static final boolean ENABLED = false;
    private static final int HEADER_BYTES = 13;
    private static final byte KIND_UNCHANGED = 0;
    private static final byte KIND_MODIFIED = 1;
    private static final byte KIND_MODIFIED_WITH_REFS = 2;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final byte[] LOCAL_REF_PREFIX =
            "com/llamalad7/mixinextras/sugar/impl/ref/generated/Local".getBytes(StandardCharsets.US_ASCII);
    private static final String LOCAL_REF_GENERATOR =
            "com.llamalad7.mixinextras.sugar.impl.ref.LocalRefClassGenerator";
    private static final String LOCAL_REF_DUMMY =
            "com.llamalad7.mixinextras.sugar.impl.ref.generated.GeneratedImplDummy";
    private static final String LOCAL_REF_CLASS_GEN_UTILS =
            "com.llamalad7.mixinextras.utils.ClassGenUtils";
    private static final String LOCAL_REF_IMPL_PACKAGE =
            "com.llamalad7.mixinextras.sugar.impl.ref.generated.Local";
    private static final String LOCAL_REF_IMPL_INTERNAL_PREFIX =
            "com/llamalad7/mixinextras/sugar/impl/ref/generated/Local";
    private static final String LOCAL_REF_INTERFACE_PREFIX =
            "com.llamalad7.mixinextras.sugar.ref.Local";
    private static final String LOCAL_REF_CACHE_PREFIX = "__localref__\0";
    private static final String[] LOCAL_REF_SUFFIXES = {
            "RefImpl",
            "BooleanRefImpl",
            "ByteRefImpl",
            "CharRefImpl",
            "DoubleRefImpl",
            "FloatRefImpl",
            "IntRefImpl",
            "LongRefImpl",
            "ShortRefImpl"
    };
    private static final byte[][] LOCAL_REF_SUFFIX_BYTES = initSuffixBytes();
    private static final String[] LOCAL_REF_TYPE_DESCRIPTORS = {
            "Ljava/lang/Object;",
            "Z",
            "B",
            "C",
            "D",
            "F",
            "I",
            "J",
            "S"
    };
    private static final Map<ClassLoader, AtomicInteger> ENSURED_LOCAL_REFS = new ConcurrentHashMap<>();
    private static final Map<ClassLoader, AtomicInteger> RECORDED_LOCAL_REFS = new ConcurrentHashMap<>();

    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();

    private TransformedClassCache() {
    }

    public static byte[] get(byte[] inputBytes, String className, String reason) {
        return get(null, inputBytes, className, reason);
    }

    public static byte[] get(ClassLoader transformingClassLoader, byte[] inputBytes, String className, String reason) {
        if (!ENABLED || inputBytes == null || (inputBytes.length == 0 && !"classloading".equals(reason))
                || className == null || !isCacheableReason(reason)) {
            return null;
        }
        String key = cacheKey(className, reason);
        byte[] entry = StartupResourceImage.transformedClass(key);
        if (entry == null || entry.length < HEADER_BYTES) {
            MISSES.increment();
            return null;
        }
        long expectedHash = readLong(entry, 0);
        int expectedLength = readInt(entry, 8);
        long actualHash = inputBytes.length == 0 ? 0L : hash64(inputBytes);
        if (expectedLength != inputBytes.length || expectedHash != actualHash) {
            MISSES.increment();
            return null;
        }
        byte kind = entry[12];
        if (kind == KIND_UNCHANGED && inputBytes.length > 0) {
            HITS.increment();
            return inputBytes;
        }
        if (kind == KIND_MODIFIED) {
            HITS.increment();
            return Arrays.copyOfRange(entry, HEADER_BYTES, entry.length);
        }
        if (kind == KIND_MODIFIED_WITH_REFS && entry.length >= HEADER_BYTES + 2) {
            int localRefMask = ((entry[HEADER_BYTES] & 0xff) << 8) | (entry[HEADER_BYTES + 1] & 0xff);
            ensureLocalRefs(transformingClassLoader, localRefMask);
            HITS.increment();
            return Arrays.copyOfRange(entry, HEADER_BYTES + 2, entry.length);
        }
        MISSES.increment();
        return null;
    }

    public static void record(byte[] resultBytes, byte[] inputBytes, String className, String reason) {
        record(null, resultBytes, inputBytes, className, reason);
    }

    public static void record(ClassLoader transformingClassLoader, byte[] resultBytes, byte[] inputBytes,
            String className, String reason) {
        if (!ENABLED || resultBytes == null || inputBytes == null || className == null || !isCacheableReason(reason)) {
            return;
        }
        if (inputBytes.length == 0 && (!"classloading".equals(reason) || resultBytes.length == 0)) {
            return;
        }
        String key = cacheKey(className, reason);
        long inputHash = inputBytes.length == 0 ? 0L : hash64(inputBytes);
        boolean unchanged = inputBytes.length > 0
                && (resultBytes == inputBytes || Arrays.equals(resultBytes, inputBytes));
        int localRefMask = unchanged ? 0 : scanLocalRefMask(resultBytes);
        if (localRefMask != 0) {
            captureLocalRefDefinitions(transformingClassLoader, localRefMask);
        }
        int extraHeader = localRefMask == 0 ? 0 : 2;
        byte[] entry = new byte[unchanged ? HEADER_BYTES : HEADER_BYTES + extraHeader + resultBytes.length];
        writeLong(entry, 0, inputHash);
        writeInt(entry, 8, inputBytes.length);
        if (unchanged) {
            entry[12] = KIND_UNCHANGED;
        } else if (localRefMask == 0) {
            entry[12] = KIND_MODIFIED;
            System.arraycopy(resultBytes, 0, entry, HEADER_BYTES, resultBytes.length);
        } else {
            entry[12] = KIND_MODIFIED_WITH_REFS;
            entry[HEADER_BYTES] = (byte) (localRefMask >>> 8);
            entry[HEADER_BYTES + 1] = (byte) localRefMask;
            System.arraycopy(resultBytes, 0, entry, HEADER_BYTES + 2, resultBytes.length);
        }
        StartupResourceImage.recordTransformedClass(key, entry);
    }

    public static String environmentFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateString(digest, "lightspeed-transformed-class-cache-v3");
            updateString(digest, System.getProperty("java.version", ""));
            updateString(digest, System.getProperty("os.name", ""));
            updateString(digest, System.getProperty("os.arch", ""));
            String owner = System.getProperty("lightspeed.agent.owner");
            Path modsDir = null;
            if (owner != null && !owner.isBlank()) {
                Path ownerPath = Path.of(owner).toAbsolutePath().normalize();
                if (Files.isRegularFile(ownerPath)) {
                    updateString(digest, ownerPath.getFileName().toString());
                    digestFileSha256(digest, ownerPath);
                    Path parent = ownerPath.getParent();
                    if (parent != null && "mods".equalsIgnoreCase(String.valueOf(parent.getFileName()))) {
                        modsDir = parent;
                    }
                }
            }
            Path instanceDir = modsDir != null ? modsDir.getParent() : Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            if (modsDir == null) {
                modsDir = instanceDir.resolve("mods");
            }
            hashDirectoryEntries(digest, modsDir, ".jar", true);
            hashMixinPropertyConfigs(digest, instanceDir.resolve("config"));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            System.err.println("[Lightspeed Agent] transformed class image unavailable: " + exception);
            return null;
        }
    }

    public static long hits() {
        return HITS.sum();
    }

    public static long misses() {
        return MISSES.sum();
    }

    private static boolean isCacheableReason(String reason) {
        return "classloading".equals(reason) || "computing_frames".equals(reason);
    }

    private static String cacheKey(String className, String reason) {
        String internalName = className.indexOf('.') >= 0 ? className.replace('.', '/') : className;
        return internalName + '\0' + reason;
    }

    private static void hashMixinPropertyConfigs(MessageDigest digest, Path configDir) throws IOException {
        updateString(digest, "mixin-props");
        if (!Files.isDirectory(configDir)) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String lower = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (lower.endsWith("mixins.properties") || lower.endsWith("-mixins.properties")) {
                        entries.add(entry);
                    }
                }
            }
        }
        entries.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path entry : entries) {
            updateString(digest, entry.getFileName().toString());
            digestFileSha256(digest, entry);
        }
    }

    private static void hashDirectoryEntries(MessageDigest digest, Path dir, String requiredSuffix, boolean isMods)
            throws IOException {
        updateString(digest, isMods ? "mods" : "config");
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (requiredSuffix.isEmpty() || name.endsWith(requiredSuffix)) {
                        entries.add(entry);
                    }
                }
            }
        }
        entries.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            updateString(digest, name);
            if (isMods && name.toLowerCase(Locale.ROOT).startsWith("lightspeed")) {
                digestFileSha256(digest, entry);
            } else if (!isMods && name.toLowerCase(Locale.ROOT).startsWith("lightspeed")) {
                digestFileSha256(digest, entry);
            } else {
                updateLong(digest, Files.size(entry));
                updateLong(digest, Files.getLastModifiedTime(entry).toMillis());
            }
        }
    }

    private static void digestFileSha256(MessageDigest digest, Path path) throws IOException {
        try {
            MessageDigest fileDigest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            digest.update(fileDigest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException(exception);
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static long hash64(byte[] data) {
        long hash = FNV_OFFSET;
        for (byte b : data) {
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static void writeLong(byte[] dst, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            dst[offset + i] = (byte) (value >>> (56 - i * 8));
        }
    }

    private static void writeInt(byte[] dst, int offset, int value) {
        dst[offset] = (byte) (value >>> 24);
        dst[offset + 1] = (byte) (value >>> 16);
        dst[offset + 2] = (byte) (value >>> 8);
        dst[offset + 3] = (byte) value;
    }

    private static long readLong(byte[] src, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (src[offset + i] & 0xffL);
        }
        return value;
    }

    private static int readInt(byte[] src, int offset) {
        return ((src[offset] & 0xff) << 24)
                | ((src[offset + 1] & 0xff) << 16)
                | ((src[offset + 2] & 0xff) << 8)
                | (src[offset + 3] & 0xff);
    }

    private static byte[][] initSuffixBytes() {
        byte[][] result = new byte[LOCAL_REF_SUFFIXES.length][];
        for (int i = 0; i < LOCAL_REF_SUFFIXES.length; i++) {
            result[i] = LOCAL_REF_SUFFIXES[i].getBytes(StandardCharsets.US_ASCII);
        }
        return result;
    }

    private static int scanLocalRefMask(byte[] resultBytes) {
        int prefixLen = LOCAL_REF_PREFIX.length;
        if (resultBytes.length < prefixLen + 7) {
            return 0;
        }
        int mask = 0;
        int limit = resultBytes.length - prefixLen - 7;
        for (int i = 0; i <= limit; i++) {
            if (resultBytes[i] != LOCAL_REF_PREFIX[0] || !matchesAt(resultBytes, i, LOCAL_REF_PREFIX)) {
                continue;
            }
            int suffixPos = i + prefixLen;
            for (int bitIndex = 0; bitIndex < LOCAL_REF_SUFFIX_BYTES.length; bitIndex++) {
                byte[] suffix = LOCAL_REF_SUFFIX_BYTES[bitIndex];
                if (matchesAt(resultBytes, suffixPos, suffix)) {
                    mask |= (1 << bitIndex);
                    break;
                }
            }
        }
        return mask;
    }

    private static boolean matchesAt(byte[] haystack, int offset, byte[] needle) {
        if (offset + needle.length > haystack.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (haystack[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void captureLocalRefDefinitions(ClassLoader loader, int mask) {
        if (mask == 0) {
            return;
        }
        ClassLoader targetLoader = loader != null ? loader : Thread.currentThread().getContextClassLoader();
        if (targetLoader == null) {
            return;
        }
        AtomicInteger recorded = RECORDED_LOCAL_REFS.computeIfAbsent(targetLoader, unused -> new AtomicInteger());
        if ((recorded.get() & mask) == mask) {
            return;
        }
        synchronized (recorded) {
            int current = recorded.get();
            int pending = mask & ~current;
            if (pending == 0) {
                return;
            }
            try {
                Class<?> classGenUtils = Class.forName(LOCAL_REF_CLASS_GEN_UTILS, false, targetLoader);
                java.lang.reflect.Field defsField = classGenUtils.getDeclaredField("DEFINITIONS");
                defsField.setAccessible(true);
                Map<String, byte[]> defs = (Map<String, byte[]>) defsField.get(null);
                if (defs == null) {
                    return;
                }
                for (int i = 0; i < LOCAL_REF_SUFFIXES.length; i++) {
                    int bit = 1 << i;
                    if ((pending & bit) == 0) {
                        continue;
                    }
                    String suffix = LOCAL_REF_SUFFIXES[i];
                    byte[] classBytes = defs.get(LOCAL_REF_IMPL_PACKAGE + suffix);
                    if (classBytes != null && classBytes.length > 0) {
                        StartupResourceImage.recordTransformedClass(LOCAL_REF_CACHE_PREFIX + suffix, classBytes);
                        current |= bit;
                        recorded.set(current);
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Best-effort capture; warm replay falls back to LocalRefClassGenerator if initialized.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void ensureLocalRefs(ClassLoader loader, int mask) {
        if (mask == 0) {
            return;
        }
        ClassLoader targetLoader = loader != null ? loader : Thread.currentThread().getContextClassLoader();
        if (targetLoader == null) {
            return;
        }
        AtomicInteger known = ENSURED_LOCAL_REFS.computeIfAbsent(targetLoader, unused -> new AtomicInteger());
        if ((known.get() & mask) == mask) {
            return;
        }
        synchronized (known) {
            int current = known.get();
            int pending = mask & ~current;
            if (pending == 0) {
                return;
            }
            java.lang.invoke.MethodHandles.Lookup dummyLookup = null;
            Map<Class<?>, String> interfaceToImpl = null;
            Map<String, byte[]> defs = null;
            java.lang.reflect.Method getForType = null;
            java.lang.reflect.Method typeFactory = null;
            for (int i = 0; i < LOCAL_REF_SUFFIXES.length; i++) {
                int bit = 1 << i;
                if ((pending & bit) == 0) {
                    continue;
                }
                String suffix = LOCAL_REF_SUFFIXES[i];
                String implClassName = LOCAL_REF_IMPL_PACKAGE + suffix;
                try {
                    Class.forName(implClassName, false, targetLoader);
                    current |= bit;
                    known.set(current);
                    continue;
                } catch (ClassNotFoundException ignored) {
                    // Define or generate the missing MixinExtras ref implementation before returning cached class bytes.
                }
                byte[] cachedBytes = StartupResourceImage.transformedClass(LOCAL_REF_CACHE_PREFIX + suffix);
                if (cachedBytes != null && cachedBytes.length > 0) {
                    try {
                        if (dummyLookup == null) {
                            Class<?> dummyClass = Class.forName(LOCAL_REF_DUMMY, true, targetLoader);
                            dummyLookup = (java.lang.invoke.MethodHandles.Lookup) dummyClass.getMethod("getLookup").invoke(null);
                            Class<?> genClass = Class.forName(LOCAL_REF_GENERATOR, true, targetLoader);
                            java.lang.reflect.Field ifaceMapField = genClass.getDeclaredField("interfaceToImpl");
                            ifaceMapField.setAccessible(true);
                            interfaceToImpl = (Map<Class<?>, String>) ifaceMapField.get(null);
                            Class<?> classGenUtils = Class.forName(LOCAL_REF_CLASS_GEN_UTILS, true, targetLoader);
                            java.lang.reflect.Field defsField = classGenUtils.getDeclaredField("DEFINITIONS");
                            defsField.setAccessible(true);
                            defs = (Map<String, byte[]>) defsField.get(null);
                        }
                        dummyLookup.defineClass(cachedBytes);
                        if (defs != null) {
                            defs.put(implClassName, cachedBytes);
                        }
                        if (interfaceToImpl != null) {
                            String ifaceName = LOCAL_REF_INTERFACE_PREFIX + suffix.substring(0, suffix.length() - 4);
                            Class<?> ifaceClass = Class.forName(ifaceName, false, targetLoader);
                            interfaceToImpl.put(ifaceClass, LOCAL_REF_IMPL_INTERNAL_PREFIX + suffix);
                        }
                        current |= bit;
                        known.set(current);
                        continue;
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalStateException("Failed to define cached MixinExtras ref class " + implClassName, exception);
                    }
                }
                try {
                    if (getForType == null) {
                        Class<?> generatorClass = Class.forName(LOCAL_REF_GENERATOR, true, targetLoader);
                        for (java.lang.reflect.Method candidate : generatorClass.getMethods()) {
                            if (candidate.getName().equals("getForType") && candidate.getParameterCount() == 1) {
                                getForType = candidate;
                                break;
                            }
                        }
                        if (getForType == null) {
                            throw new IllegalStateException("Missing getForType on " + LOCAL_REF_GENERATOR);
                        }
                        Class<?> asmTypeClass = getForType.getParameterTypes()[0];
                        typeFactory = asmTypeClass.getMethod("getType", String.class);
                    }
                    Object asmType = typeFactory.invoke(null, LOCAL_REF_TYPE_DESCRIPTORS[i]);
                    getForType.invoke(null, asmType);
                    current |= bit;
                    known.set(current);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Failed to synthesize MixinExtras ref class " + implClassName, exception);
                }
            }
        }
    }
}
