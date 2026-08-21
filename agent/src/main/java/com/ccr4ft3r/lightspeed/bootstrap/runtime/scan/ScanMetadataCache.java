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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScanMetadataCache {
    private static final int MAGIC = 0x4c53534d;
    private static final int VERSION = 1;
    private static final int MAX_COLLECTION_SIZE = 100_000;
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("lightspeed.scanMetadataCache", "true"));
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder RECORDED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> type) {
            try {
                return new Access(type);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("unsupported Forge scan metadata model", exception);
            }
        }
    };

    private ScanMetadataCache() {
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
        byte[] encoded = StartupResourceImage.scanMetadata(key);
        if (encoded == null) {
            MISSES.increment();
            return false;
        }
        try {
            ACCESS.get(scanData.getClass()).decodeInto(scanData, encoded);
            HITS.increment();
            return true;
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError exception) {
            FAILURES.increment();
            logFailure("replay", exception);
            return false;
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
        try {
            byte[] encoded = ACCESS.get(scanData.getClass()).encodeClass(path, scanData);
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

        private Access(Class<?> scanDataType) throws ReflectiveOperationException {
            ClassLoader loader = scanDataType.getClassLoader();
            Class<?> classData = loader.loadClass("net.minecraftforge.forgespi.language.ModFileScanData$ClassData");
            Class<?> annotationData = loader.loadClass("net.minecraftforge.forgespi.language.ModFileScanData$AnnotationData");
            this.enumHolder = loader.loadClass("net.minecraftforge.fml.loading.moddiscovery.ModAnnotation$EnumHolder");
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
            this.enumDescriptor = enumHolder.getMethod("getDesc");
            this.enumValue = enumHolder.getMethod("getValue");
        }

        private byte[] encodeClass(Path path, Object scanData) throws ReflectiveOperationException, IOException {
            Set<?> classes = (Set<?>) getClasses.invoke(scanData);
            Object selected = null;
            String descriptor = null;
            String normalizedPath = ResourceMembershipIndex.normalize(path.toString());
            for (Object candidate : classes) {
                String candidateDescriptor = descriptor(classType.invoke(candidate));
                String classPath = candidateDescriptor.substring(1, candidateDescriptor.length() - 1) + ".class";
                if (normalizedPath.endsWith(classPath)) {
                    if (selected != null) {
                        return null;
                    }
                    selected = candidate;
                    descriptor = candidateDescriptor;
                }
            }
            if (selected == null) {
                return null;
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                writeString(output, descriptor);
                writeNullableString(output, descriptor(parentType.invoke(selected)));
                List<String> interfaceDescriptors = new ArrayList<>();
                for (Object type : (Set<?>) interfaces.invoke(selected)) {
                    interfaceDescriptors.add(descriptor(type));
                }
                interfaceDescriptors.sort(Comparator.naturalOrder());
                output.writeInt(interfaceDescriptors.size());
                for (String value : interfaceDescriptors) {
                    writeString(output, value);
                }

                List<Object> annotations = new ArrayList<>();
                for (Object annotation : (Set<?>) getAnnotations.invoke(scanData)) {
                    if (descriptor.equals(descriptor(annotationClass.invoke(annotation)))) {
                        annotations.add(annotation);
                    }
                }
                output.writeInt(annotations.size());
                for (Object annotation : annotations) {
                    writeString(output, descriptor(annotationType.invoke(annotation)));
                    writeString(output, ((ElementType) targetType.invoke(annotation)).name());
                    writeNullableString(output, (String) memberName.invoke(annotation));
                    writeMap(output, (Map<?, ?>) annotationValues.invoke(annotation));
                }
            }
            return bytes.toByteArray();
        }

        @SuppressWarnings("unchecked")
        private void decodeInto(Object scanData, byte[] encoded) throws ReflectiveOperationException, IOException {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw new IOException("unsupported scan metadata cache");
                }
                Object clazz = type(readString(input));
                Object parent = type(readNullableString(input));
                int interfaceCount = readCount(input);
                Set<Object> interfaceTypes = new LinkedHashSet<>();
                for (int index = 0; index < interfaceCount; index++) {
                    interfaceTypes.add(type(readString(input)));
                }
                ((Set<Object>) getClasses.invoke(scanData)).add(
                        classDataConstructor.newInstance(clazz, parent, Set.copyOf(interfaceTypes)));

                int annotationCount = readCount(input);
                Set<Object> annotations = (Set<Object>) getAnnotations.invoke(scanData);
                for (int index = 0; index < annotationCount; index++) {
                    Object annotation = type(readString(input));
                    ElementType target = ElementType.valueOf(readString(input));
                    String member = readNullableString(input);
                    Map<String, Object> values = readMap(input);
                    annotations.add(annotationDataConstructor.newInstance(annotation, target, clazz, member, values));
                }
                if (input.read() != -1) {
                    throw new IOException("trailing scan metadata bytes");
                }
            }
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
                values.put(readString(input), readValue(input));
            }
            return values;
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
            return descriptor == null ? null : typeFromDescriptor.invoke(null, descriptor);
        }

        private String descriptor(Object value) throws ReflectiveOperationException {
            return value == null ? null : (String) typeDescriptor.invoke(value);
        }
    }

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

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) {
            throw new IOException("invalid scan metadata string length " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated scan metadata string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void logFailure(String operation, Throwable throwable) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            System.err.println("[Lightspeed Agent] scan metadata cache could not " + operation + ": " + throwable);
            throwable.printStackTrace(System.err);
        }
    }
}
