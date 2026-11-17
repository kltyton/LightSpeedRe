package com.ccr4ft3r.lightspeed.bootstrap.runtime.scan;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.image.StartupResourceImage;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScanMetadataCache {
    private static final int MAGIC = 0x4c53534d;
    private static final int VERSION = 2;
    private static final int MAX_COLLECTION_SIZE = 100_000;
    private static final int MAX_STANDALONE_ENTRY_BYTES = 32 * 1024 * 1024;
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("lightspeed.scanMetadataCache", "true"));
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder RECORDED = new LongAdder();
    private static final LongAdder INCOMPLETE = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final Set<String> ACTIVE_KEYS = ConcurrentHashMap.newKeySet();
    private static final Set<Access> ACCESS_INSTANCES = ConcurrentHashMap.newKeySet();
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> type) {
            try {
                Access created = new Access(type);
                ACCESS_INSTANCES.add(created);
                return created;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("unsupported Forge scan metadata model", exception);
            }
        }
    };

    public static void releaseDecodeCaches() {
        for (Access access : ACCESS_INSTANCES) {
            access.typeCache.clear();
            access.keyCache.clear();
        }
    }
    private static final ClassValue<ModAccess> MOD_ACCESS = new ClassValue<>() {
        @Override
        protected ModAccess computeValue(Class<?> type) {
            try {
                return new ModAccess(type);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("unsupported Forge ModFile model", exception);
            }
        }
    };

    private ScanMetadataCache() {
    }

    public static boolean replay(Object modFile, Object scanData) {
        if (!ENABLED || modFile == null || scanData == null) {
            return false;
        }
        try {
            ModAccess modAccess = MOD_ACCESS.get(modFile.getClass());
            if (modAccess.hasSecurityData(modFile) || !modAccess.usesStandardScanProvider(modFile)) {
                return false;
            }
            Path root = modAccess.rootPath(modFile);
            String key = ResourceMembershipIndex.persistentPathKey(root);
            if (key == null) {
                MISSES.increment();
                return false;
            }
            ACTIVE_KEYS.add(key);
            byte[] encoded = StartupResourceImage.scanMetadata(key);
            if (encoded == null) {
                MISSES.increment();
                return false;
            }
            Access access = ACCESS.get(scanData.getClass());
            DecodedScan decoded = access.decode(encoded);
            modAccess.restoreUnsignedStatus(modFile, decoded.classes().size());
            access.publish(scanData, decoded);
            HITS.increment();
            return true;
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError exception) {
            FAILURES.increment();
            logFailure("replay aggregate Mod scan", exception);
            return false;
        }
    }

    public static boolean replay(Path path, Object scanData) {
        if (!ENABLED) {
            return false;
        }
        String key = ResourceMembershipIndex.persistentPathKey(path);
        if (key == null || scanData == null) {
            MISSES.increment();
            return false;
        }
        ACTIVE_KEYS.add(key);
        byte[] encoded = StartupResourceImage.scanMetadata(key);
        if (encoded == null) {
            MISSES.increment();
            return false;
        }
        try {
            Access access = ACCESS.get(scanData.getClass());
            access.publish(scanData, access.decode(encoded));
            HITS.increment();
            return true;
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError exception) {
            FAILURES.increment();
            logFailure("replay", exception);
            return false;
        }
    }

    public static void record(Object modFile, Object scanData) {
        if (!ENABLED || modFile == null || scanData == null) {
            return;
        }
        try {
            ModAccess modAccess = MOD_ACCESS.get(modFile.getClass());
            if (modAccess.hasSecurityData(modFile) || !modAccess.usesStandardScanProvider(modFile)) {
                return;
            }
            Path root = modAccess.rootPath(modFile);
            int expected = ResourceMembershipIndex.classEntryCount(root);
            int actual = ACCESS.get(scanData.getClass()).classCount(scanData);
            if (expected == ResourceMembershipIndex.UNKNOWN || actual != expected) {
                INCOMPLETE.increment();
                return;
            }
            record(root, scanData);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            FAILURES.increment();
            logFailure("record aggregate Mod scan", exception);
        }
    }

    public static void record(Path path, Object scanData) {
        if (!ENABLED) {
            return;
        }
        String key = ResourceMembershipIndex.persistentPathKey(path);
        if (key == null || scanData == null) {
            return;
        }
        ACTIVE_KEYS.add(key);
        try {
            byte[] encoded = ACCESS.get(scanData.getClass()).encode(scanData);
            if (encoded != null) {
                StartupResourceImage.recordScanMetadata(key, encoded);
                RECORDED.increment();
            }
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError exception) {
            FAILURES.increment();
            logFailure("record", exception);
        }
    }

    public static long hits() {
        return HITS.sum();
    }

    public static long misses() {
        return MISSES.sum();
    }

    public static long recorded() {
        return RECORDED.sum();
    }

    public static long failures() {
        return FAILURES.sum();
    }

    public static long incomplete() {
        return INCOMPLETE.sum();
    }

    public static Set<String> activeKeys() {
        return Set.copyOf(ACTIVE_KEYS);
    }

    public static boolean standaloneContains(String key) {
        if (!ENABLED || !isStandaloneKey(key)) {
            return false;
        }
        ACTIVE_KEYS.add(key);
        return StartupResourceImage.scanMetadata(key) != null;
    }

    public static boolean standaloneAvailable() {
        return ENABLED;
    }

    public static byte[] standaloneEncode(Object scanData) throws IOException {
        if (!ENABLED || scanData == null) {
            throw new IOException("scan metadata cache is unavailable");
        }
        try {
            byte[] encoded = ACCESS.get(scanData.getClass()).encode(scanData);
            if (encoded.length == 0 || encoded.length > MAX_STANDALONE_ENTRY_BYTES) {
                throw new IOException("scan metadata exceeds standalone entry bounds");
            }
            return encoded;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            throw new IOException("unable to encode loader scan metadata", exception);
        }
    }

    public static boolean standaloneRecord(String key, byte[] encoded) {
        if (!ENABLED || !isStandaloneKey(key) || encoded == null || encoded.length == 0
                || encoded.length > MAX_STANDALONE_ENTRY_BYTES) {
            return false;
        }
        try {
            cachedClassCount(encoded);
            ACTIVE_KEYS.add(key);
            StartupResourceImage.recordScanMetadata(key, encoded);
            return Arrays.equals(encoded, StartupResourceImage.scanMetadata(key));
        } catch (IOException | RuntimeException | LinkageError exception) {
            FAILURES.increment();
            logFailure("standalone record", exception);
            return false;
        }
    }

    public static boolean standalonePersist() {
        if (!ENABLED) {
            return false;
        }
        try {
            return StartupResourceImage.persistScanMetadata(ACTIVE_KEYS);
        } catch (RuntimeException | LinkageError exception) {
            FAILURES.increment();
            logFailure("standalone persist", exception);
            return false;
        }
    }

    private static int cachedClassCount(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("unsupported scan metadata cache");
            }
            return readCount(input);
        }
    }

    private static boolean isStandaloneKey(String key) {
        return key != null && key.indexOf('\0') > 0;
    }

    private static final class ModAccess {
        private final Method findResource;
        private final Method getSecureJar;
        private final Method getProvider;
        private final Method scanFile;
        private final Method setSecurityStatus;
        private final Method hasSecurityData;
        private final Object unverified;
        private final Object invalid;

        private ModAccess(Class<?> modFileType) throws ReflectiveOperationException {
            RuntimeModuleAccess.openToAgent(modFileType);
            this.findResource = modFileType.getMethod("findResource", String[].class);
            this.getSecureJar = modFileType.getMethod("getSecureJar");
            Method providerMethod;
            try {
                providerMethod = modFileType.getMethod("getProvider");
            } catch (NoSuchMethodException unavailable) {
                providerMethod = null;
            }
            this.getProvider = providerMethod;
            this.scanFile = modFileType.getMethod("scanFile", java.util.function.Consumer.class);
            Class<?> secureJarType = getSecureJar.getReturnType();
            this.hasSecurityData = secureJarType.getMethod("hasSecurityData");
            this.setSecurityStatus = statusSetter(modFileType);
            Class<?> statusType = setSecurityStatus.getParameterTypes()[0];
            this.unverified = enumConstant(statusType, "UNVERIFIED");
            this.invalid = enumConstant(statusType, "INVALID");
        }

        private Path rootPath(Object modFile) throws ReflectiveOperationException {
            return (Path) findResource.invoke(modFile, (Object) new String[]{""});
        }

        private boolean hasSecurityData(Object modFile) throws ReflectiveOperationException {
            Object secureJar = getSecureJar.invoke(modFile);
            return (Boolean) hasSecurityData.invoke(secureJar);
        }

        private boolean usesStandardScanProvider(Object modFile) throws ReflectiveOperationException {
            if (getProvider == null) {
                return scanFile.getDeclaringClass().getName().equals(
                        "net.neoforged.fml.loading.moddiscovery.ModFile");
            }
            Object provider = getProvider.invoke(modFile);
            if (provider == null) return false;
            for (Method method : provider.getClass().getMethods()) {
                if (method.getName().equals("scanFile") && method.getParameterCount() == 2) {
                    return method.getDeclaringClass().getName().equals(
                            "net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModProvider");
                }
            }
            return false;
        }

        private void restoreUnsignedStatus(Object modFile, int classCount) throws ReflectiveOperationException {
            setSecurityStatus.invoke(modFile, classCount == 0 ? invalid : unverified);
        }

        private static Object enumConstant(Class<?> type, String name) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(name)) {
                    return constant;
                }
            }
            throw new IllegalStateException("missing SecureJar status " + name);
        }

        private static Method statusSetter(Class<?> modFileType) throws NoSuchMethodException {
            for (Method method : modFileType.getMethods()) {
                if (method.getName().equals("setSecurityStatus") && method.getParameterCount() == 1) {
                    return method;
                }
            }
            throw new NoSuchMethodException(modFileType.getName() + ".setSecurityStatus");
        }
    }

    private static final class Access {
        private static final int NULL = 0;
        private static final int STRING = 1;
        private static final int BOOLEAN = 2;
        private static final int BYTE = 3;
        private static final int CHARACTER = 4;
        private static final int SHORT = 5;
        private static final int INTEGER = 6;
        private static final int LONG = 7;
        private static final int FLOAT = 8;
        private static final int DOUBLE = 9;
        private static final int TYPE = 10;
        private static final int ENUM = 11;
        private static final int LIST = 12;
        private static final int MAP = 13;
        private static final int BYTE_ARRAY = 14;
        private static final int BOOLEAN_ARRAY = 15;
        private static final int CHARACTER_ARRAY = 16;
        private static final int SHORT_ARRAY = 17;
        private static final int INTEGER_ARRAY = 18;
        private static final int LONG_ARRAY = 19;
        private static final int FLOAT_ARRAY = 20;
        private static final int DOUBLE_ARRAY = 21;

        private final Method getClasses;
        private final Method getAnnotations;
        private final Constructor<?> classDataConstructor;
        private final Method classType;
        private final Method parentType;
        private final Method interfaces;
        private final Constructor<?> annotationDataConstructor;
        private final Method annotationType;
        private final Method targetType;
        private final Method annotationClass;
        private final Method memberName;
        private final Method annotationValues;
        private final Class<?> asmType;
        private final Method typeFromDescriptor;
        private final Method typeDescriptor;
        private final Class<?> enumHolder;
        private final Constructor<?> enumHolderConstructor;
        private final Method enumDescriptor;
        private final Method enumValue;
        private final ConcurrentHashMap<String, Object> typeCache = new ConcurrentHashMap<>(65536);
        private final ConcurrentHashMap<String, String> keyCache = new ConcurrentHashMap<>(4096);

        private Access(Class<?> scanDataType) throws ReflectiveOperationException {
            ClassLoader loader = scanDataType.getClassLoader();
            Class<?> classData = loader.loadClass(ScanRuntimeTypes.SCAN_DATA + "$ClassData");
            Class<?> annotationData = loader.loadClass(ScanRuntimeTypes.SCAN_DATA + "$AnnotationData");
            this.enumHolder = loader.loadClass(ScanRuntimeTypes.ENUM_HOLDER);
            RuntimeModuleAccess.openToAgent(scanDataType);
            RuntimeModuleAccess.openToAgent(classData);
            RuntimeModuleAccess.openToAgent(annotationData);
            RuntimeModuleAccess.openToAgent(enumHolder);
            this.getClasses = scanDataType.getMethod("getClasses");
            this.getAnnotations = scanDataType.getMethod("getAnnotations");
            this.classType = classData.getMethod("clazz");
            this.asmType = classType.getReturnType();
            this.classDataConstructor = classData.getConstructor(asmType, asmType, Set.class);
            this.parentType = classData.getMethod("parent");
            this.interfaces = classData.getMethod("interfaces");
            this.annotationDataConstructor = annotationData.getConstructor(
                    asmType, ElementType.class, asmType, String.class, Map.class);
            this.annotationType = annotationData.getMethod("annotationType");
            this.targetType = annotationData.getMethod("targetType");
            this.annotationClass = annotationData.getMethod("clazz");
            this.memberName = annotationData.getMethod("memberName");
            this.annotationValues = annotationData.getMethod("annotationData");
            this.typeFromDescriptor = asmType.getMethod("getType", String.class);
            this.typeDescriptor = asmType.getMethod("getDescriptor");
            this.enumHolderConstructor = enumHolder.getConstructor(String.class, String.class);
            this.enumDescriptor = enumHolder.getMethod(ScanRuntimeTypes.ENUM_DESCRIPTOR_GETTER);
            this.enumValue = enumHolder.getMethod(ScanRuntimeTypes.ENUM_VALUE_GETTER);
        }

        private int classCount(Object scanData) throws ReflectiveOperationException {
            return ((Set<?>) getClasses.invoke(scanData)).size();
        }

        private byte[] encode(Object scanData) throws ReflectiveOperationException, IOException {
            Set<?> classes = (Set<?>) getClasses.invoke(scanData);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(classes.size());
                for (Object classData : classes) {
                    writeString(output, descriptor(classType.invoke(classData)));
                    writeNullableString(output, descriptor(parentType.invoke(classData)));
                    List<String> interfaceDescriptors = new ArrayList<>();
                    for (Object type : (Set<?>) interfaces.invoke(classData)) {
                        interfaceDescriptors.add(descriptor(type));
                    }
                    interfaceDescriptors.sort(Comparator.naturalOrder());
                    output.writeInt(interfaceDescriptors.size());
                    for (String value : interfaceDescriptors) {
                        writeString(output, value);
                    }
                }

                Set<?> annotations = (Set<?>) getAnnotations.invoke(scanData);
                output.writeInt(annotations.size());
                for (Object annotation : annotations) {
                    writeString(output, descriptor(annotationType.invoke(annotation)));
                    writeString(output, ((ElementType) targetType.invoke(annotation)).name());
                    writeString(output, descriptor(annotationClass.invoke(annotation)));
                    writeNullableString(output, (String) memberName.invoke(annotation));
                    writeMap(output, (Map<?, ?>) annotationValues.invoke(annotation));
                }
            }
            return bytes.toByteArray();
        }

        private DecodedScan decode(byte[] encoded) throws ReflectiveOperationException, IOException {
            try (DataInputStream input = new DataInputStream(new UnsyncedByteArrayInputStream(encoded))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw new IOException("unsupported scan metadata cache");
                }
                int classCount = readCount(input);
                List<Object> classes = new ArrayList<>(classCount);
                for (int classIndex = 0; classIndex < classCount; classIndex++) {
                    Object clazz = type(readString(input));
                    Object parent = type(readNullableString(input));
                    int interfaceCount = readCount(input);
                    Set<Object> interfaceTypes;
                    if (interfaceCount == 0) {
                        interfaceTypes = Set.of();
                    } else if (interfaceCount == 1) {
                        interfaceTypes = Set.of(type(readString(input)));
                    } else if (interfaceCount == 2) {
                        Object t0 = type(readString(input));
                        Object t1 = type(readString(input));
                        interfaceTypes = t0.equals(t1) ? Set.of(t0) : Set.of(t0, t1);
                    } else {
                        Set<Object> set = new LinkedHashSet<>(interfaceCount * 4 / 3 + 1);
                        for (int index = 0; index < interfaceCount; index++) {
                            set.add(type(readString(input)));
                        }
                        interfaceTypes = Set.copyOf(set);
                    }
                    classes.add(classDataConstructor.newInstance(clazz, parent, interfaceTypes));
                }

                int annotationCount = readCount(input);
                List<Object> annotations = new ArrayList<>(annotationCount);
                for (int index = 0; index < annotationCount; index++) {
                    Object annotation = type(readString(input));
                    ElementType target = ElementType.valueOf(readString(input));
                    Object clazz = type(readString(input));
                    String member = readNullableString(input);
                    Map<String, Object> values = readMap(input);
                    annotations.add(annotationDataConstructor.newInstance(annotation, target, clazz, member, values));
                }
                if (input.read() != -1) {
                    throw new IOException("trailing scan metadata bytes");
                }
                return new DecodedScan(classes, annotations);
            }
        }

        /** Invalid images must leave the native scanner's collections untouched. */
        @SuppressWarnings("unchecked")
        private void publish(Object scanData, DecodedScan decoded) throws ReflectiveOperationException {
            Set<Object> classes = (Set<Object>) getClasses.invoke(scanData);
            Set<Object> annotations = (Set<Object>) getAnnotations.invoke(scanData);
            classes.addAll(decoded.classes());
            annotations.addAll(decoded.annotations());
        }

        private void writeMap(DataOutputStream output, Map<?, ?> values) throws IOException, ReflectiveOperationException {
            output.writeInt(values.size());
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IOException("non-string annotation key");
                }
                writeString(output, key);
                writeValue(output, entry.getValue());
            }
        }

        private Map<String, Object> readMap(DataInputStream input) throws IOException, ReflectiveOperationException {
            int count = readCount(input);
            Map<String, Object> values = new LinkedHashMap<>(Math.max(16, count * 2));
            for (int index = 0; index < count; index++) {
                values.put(internKey(readString(input)), readValue(input));
            }
            return values;
        }

        private String internKey(String key) {
            if (key == null || key.length() > 128) {
                return key;
            }
            String cached = keyCache.get(key);
            if (cached != null) {
                return cached;
            }
            if (keyCache.size() < 4096) {
                String raced = keyCache.putIfAbsent(key, key);
                if (raced != null) {
                    return raced;
                }
            }
            return key;
        }

        private void writeValue(DataOutputStream output, Object value) throws IOException, ReflectiveOperationException {
            if (value == null) {
                output.writeByte(NULL);
            } else if (value instanceof String string) {
                output.writeByte(STRING);
                writeString(output, string);
            } else if (value instanceof Boolean bool) {
                output.writeByte(BOOLEAN);
                output.writeBoolean(bool);
            } else if (value instanceof Byte number) {
                output.writeByte(BYTE);
                output.writeByte(number);
            } else if (value instanceof Character character) {
                output.writeByte(CHARACTER);
                output.writeChar(character);
            } else if (value instanceof Short number) {
                output.writeByte(SHORT);
                output.writeShort(number);
            } else if (value instanceof Integer number) {
                output.writeByte(INTEGER);
                output.writeInt(number);
            } else if (value instanceof Long number) {
                output.writeByte(LONG);
                output.writeLong(number);
            } else if (value instanceof Float number) {
                output.writeByte(FLOAT);
                output.writeFloat(number);
            } else if (value instanceof Double number) {
                output.writeByte(DOUBLE);
                output.writeDouble(number);
            } else if (asmType.isInstance(value)) {
                output.writeByte(TYPE);
                writeString(output, descriptor(value));
            } else if (enumHolder.isInstance(value)) {
                output.writeByte(ENUM);
                writeString(output, (String) enumDescriptor.invoke(value));
                writeString(output, (String) enumValue.invoke(value));
            } else if (value instanceof List<?> list) {
                output.writeByte(LIST);
                output.writeInt(list.size());
                for (Object item : list) {
                    writeValue(output, item);
                }
            } else if (value instanceof Map<?, ?> map) {
                output.writeByte(MAP);
                writeMap(output, map);
            } else if (value instanceof byte[] array) {
                output.writeByte(BYTE_ARRAY);
                output.writeInt(array.length);
                output.write(array);
            } else if (value instanceof boolean[] array) {
                output.writeByte(BOOLEAN_ARRAY);
                output.writeInt(array.length);
                for (boolean item : array) output.writeBoolean(item);
            } else if (value instanceof char[] array) {
                output.writeByte(CHARACTER_ARRAY);
                output.writeInt(array.length);
                for (char item : array) output.writeChar(item);
            } else if (value instanceof short[] array) {
                output.writeByte(SHORT_ARRAY);
                output.writeInt(array.length);
                for (short item : array) output.writeShort(item);
            } else if (value instanceof int[] array) {
                output.writeByte(INTEGER_ARRAY);
                output.writeInt(array.length);
                for (int item : array) output.writeInt(item);
            } else if (value instanceof long[] array) {
                output.writeByte(LONG_ARRAY);
                output.writeInt(array.length);
                for (long item : array) output.writeLong(item);
            } else if (value instanceof float[] array) {
                output.writeByte(FLOAT_ARRAY);
                output.writeInt(array.length);
                for (float item : array) output.writeFloat(item);
            } else if (value instanceof double[] array) {
                output.writeByte(DOUBLE_ARRAY);
                output.writeInt(array.length);
                for (double item : array) output.writeDouble(item);
            } else {
                throw new IOException("unsupported annotation value " + value.getClass().getName());
            }
        }

        private Object readValue(DataInputStream input) throws IOException, ReflectiveOperationException {
            return switch (input.readUnsignedByte()) {
                case NULL -> null;
                case STRING -> readString(input);
                case BOOLEAN -> input.readBoolean();
                case BYTE -> input.readByte();
                case CHARACTER -> input.readChar();
                case SHORT -> input.readShort();
                case INTEGER -> input.readInt();
                case LONG -> input.readLong();
                case FLOAT -> input.readFloat();
                case DOUBLE -> input.readDouble();
                case TYPE -> type(readString(input));
                case ENUM -> enumHolderConstructor.newInstance(readString(input), readString(input));
                case LIST -> {
                    int count = readCount(input);
                    List<Object> values = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        values.add(readValue(input));
                    }
                    yield values;
                }
                case MAP -> readMap(input);
                case BYTE_ARRAY -> readByteArray(input);
                case BOOLEAN_ARRAY -> {
                    boolean[] values = new boolean[readCount(input)];
                    for (int index = 0; index < values.length; index++) values[index] = input.readBoolean();
                    yield values;
                }
                case CHARACTER_ARRAY -> {
                    char[] values = new char[readCount(input)];
                    for (int index = 0; index < values.length; index++) values[index] = input.readChar();
                    yield values;
                }
                case SHORT_ARRAY -> {
                    short[] values = new short[readCount(input)];
                    for (int index = 0; index < values.length; index++) values[index] = input.readShort();
                    yield values;
                }
                case INTEGER_ARRAY -> {
                    int[] values = new int[readCount(input)];
                    for (int index = 0; index < values.length; index++) values[index] = input.readInt();
                    yield values;
                }
                case LONG_ARRAY -> {
                    long[] values = new long[readCount(input)];
                    for (int index = 0; index < values.length; index++) values[index] = input.readLong();
                    yield values;
                }
                case FLOAT_ARRAY -> {
                    float[] values = new float[readCount(input)];
                    for (int index = 0; index < values.length; index++) values[index] = input.readFloat();
                    yield values;
                }
                case DOUBLE_ARRAY -> {
                    double[] values = new double[readCount(input)];
                    for (int index = 0; index < values.length; index++) values[index] = input.readDouble();
                    yield values;
                }
                default -> throw new IOException("unknown annotation value tag");
            };
        }

        private Object type(String descriptor) throws ReflectiveOperationException {
            if (descriptor == null) {
                return null;
            }
            Object cached = typeCache.get(descriptor);
            if (cached != null) {
                return cached;
            }
            Object created = typeFromDescriptor.invoke(null, descriptor);
            if (created != null && typeCache.size() < 262144) {
                Object raced = typeCache.putIfAbsent(descriptor, created);
                if (raced != null) {
                    return raced;
                }
            }
            return created;
        }

        private String descriptor(Object value) throws ReflectiveOperationException {
            return value == null ? null : (String) typeDescriptor.invoke(value);
        }
    }

    private record DecodedScan(List<Object> classes, List<Object> annotations) { }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("invalid scan metadata collection size " + count);
        }
        return count;
    }

    private static byte[] readByteArray(DataInputStream input) throws IOException {
        int length = readCount(input);
        byte[] values = input.readNBytes(length);
        if (values.length != length) {
            throw new IOException("truncated scan metadata byte array");
        }
        return values;
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            writeString(output, value);
        }
    }

    private static String readNullableString(DataInputStream input) throws IOException {
        return input.readBoolean() ? readString(input) : null;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static final ThreadLocal<byte[]> STRING_SCRATCH = ThreadLocal.withInitial(() -> new byte[512]);

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) {
            throw new IOException("invalid scan metadata string length " + length);
        }
        if (length == 0) {
            return "";
        }
        if (length <= 512) {
            byte[] scratch = STRING_SCRATCH.get();
            input.readFully(scratch, 0, length);
            return new String(scratch, 0, length, StandardCharsets.UTF_8);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated scan metadata string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class UnsyncedByteArrayInputStream extends java.io.InputStream {
        private final byte[] buf;
        private int pos;

        private UnsyncedByteArrayInputStream(byte[] buf) {
            this.buf = buf;
        }

        @Override
        public int read() {
            return pos < buf.length ? (buf[pos++] & 0xff) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len <= 0) {
                return 0;
            }
            int avail = buf.length - pos;
            if (avail <= 0) {
                return -1;
            }
            int count = Math.min(len, avail);
            System.arraycopy(buf, pos, b, off, count);
            pos += count;
            return count;
        }
    }

    private static void logFailure(String operation, Throwable throwable) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            System.err.println("[Lightspeed Agent] scan metadata cache could not " + operation + ": " + throwable);
            throwable.printStackTrace(System.err);
        }
    }
}
