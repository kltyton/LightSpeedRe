package com.ccr4ft3r.lightspeed.cache.persistence;

import net.minecraft.server.packs.PackType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class NeutralCacheCodec {
    static final long MAX_FILE_BYTES = 256L * 1024L * 1024L;
    private static final int MAGIC = 0x4c534e43;
    private static final int VERSION = 1;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_OBJECTS = 1_000_000;
    private static final int MAX_COLLECTION_SIZE = 1_000_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAP = 1;
    private static final int STRING = 2;
    private static final int BOOLEAN = 3;
    private static final int PACK_TYPE = 4;
    private static final int LIST = 5;
    private static final int SET = 6;

    private NeutralCacheCodec() {
    }

    static void write(Map<?, ?> value, DataOutputStream output) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        writeValue(output, value, 0, new Budget());
    }

    static Map<?, ?> read(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            throw new IOException("unsupported neutral cache header");
        }
        Object value = readValue(input, 0, new Budget());
        if (!(value instanceof Map<?, ?> map) || input.read() != -1) {
            throw new IOException("neutral cache root is not one complete map");
        }
        return map;
    }

    private static void writeValue(DataOutputStream output, Object value, int depth, Budget budget)
            throws IOException {
        budget.take(depth);
        if (value instanceof Map<?, ?> map) {
            output.writeByte(MAP);
            writeCount(output, map.size());
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            for (Map.Entry<?, ?> entry : entries) {
                canonicalKey(entry.getKey());
            }
            entries.sort(Comparator.comparing(entry -> canonicalKeyUnchecked(entry.getKey())));
            for (Map.Entry<?, ?> entry : entries) {
                writeValue(output, entry.getKey(), depth + 1, budget);
                writeValue(output, entry.getValue(), depth + 1, budget);
            }
        } else if (value instanceof String string) {
            output.writeByte(STRING);
            writeString(output, string);
        } else if (value instanceof Boolean bool) {
            output.writeByte(BOOLEAN);
            output.writeBoolean(bool);
        } else if (value instanceof PackType packType) {
            output.writeByte(PACK_TYPE);
            writeString(output, packType.name());
        } else if (value instanceof List<?> list) {
            output.writeByte(LIST);
            writeCount(output, list.size());
            for (Object element : list) {
                writeValue(output, element, depth + 1, budget);
            }
        } else if (value instanceof Set<?> set) {
            output.writeByte(SET);
            writeCount(output, set.size());
            List<Object> values = new ArrayList<>(set);
            for (Object element : values) {
                canonicalKey(element);
            }
            values.sort(Comparator.comparing(NeutralCacheCodec::canonicalKeyUnchecked));
            for (Object element : values) {
                writeValue(output, element, depth + 1, budget);
            }
        } else {
            throw new IOException("unsupported neutral cache value "
                    + (value == null ? "null" : value.getClass().getName()));
        }
    }

    private static Object readValue(DataInputStream input, int depth, Budget budget) throws IOException {
        budget.take(depth);
        return switch (input.readUnsignedByte()) {
            case MAP -> {
                int count = readCount(input);
                Map<Object, Object> map = new ConcurrentHashMap<>(Math.max(16, count * 2));
                for (int index = 0; index < count; index++) {
                    Object key = readValue(input, depth + 1, budget);
                    Object value = readValue(input, depth + 1, budget);
                    if (!(key instanceof String || key instanceof PackType) || map.putIfAbsent(key, value) != null) {
                        throw new IOException("invalid or duplicate neutral cache map key");
                    }
                }
                yield map;
            }
            case STRING -> readString(input);
            case BOOLEAN -> input.readBoolean();
            case PACK_TYPE -> {
                try {
                    yield PackType.valueOf(readString(input));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("unknown cached PackType", exception);
                }
            }
            case LIST -> {
                int count = readCount(input);
                List<Object> list = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    list.add(readValue(input, depth + 1, budget));
                }
                yield List.copyOf(list);
            }
            case SET -> {
                int count = readCount(input);
                Set<Object> set = ConcurrentHashMap.newKeySet(Math.max(16, count));
                for (int index = 0; index < count; index++) {
                    if (!set.add(readValue(input, depth + 1, budget))) {
                        throw new IOException("duplicate neutral cache set value");
                    }
                }
                yield Set.copyOf(set);
            }
            default -> throw new IOException("unknown neutral cache value tag");
        };
    }

    private static String canonicalKey(Object value) throws IOException {
        if (value instanceof String string) {
            return "S\0" + string;
        }
        if (value instanceof PackType packType) {
            return "P\0" + packType.name();
        }
        throw new IOException("unsupported neutral cache key "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static String canonicalKeyUnchecked(Object value) {
        if (value instanceof String string) {
            return "S\0" + string;
        }
        if (value instanceof PackType packType) {
            return "P\0" + packType.name();
        }
        throw new IllegalStateException("neutral cache key changed during encoding");
    }

    private static void writeCount(DataOutputStream output, int count) throws IOException {
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("neutral cache collection exceeds bounds");
        }
        output.writeInt(count);
    }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("neutral cache collection exceeds bounds");
        }
        return count;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("neutral cache string exceeds bounds");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("neutral cache string exceeds bounds");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated neutral cache string");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("invalid neutral cache UTF-8", exception);
        }
    }

    private static final class Budget {
        private int objects;

        private void take(int depth) throws IOException {
            if (depth > MAX_DEPTH || ++objects > MAX_OBJECTS) {
                throw new IOException("neutral cache object graph exceeds bounds");
            }
        }
    }
}
