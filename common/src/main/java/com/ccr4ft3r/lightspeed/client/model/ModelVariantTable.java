package com.ccr4ft3r.lightspeed.client.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class ModelVariantTable {
    private static final int MAX_TABLES = 1024;
    private static final int MAX_VARIANTS_PER_TABLE = 65_536;
    private static final int MAX_RETAINED_VARIANTS = 1_048_576;
    private static final int MAX_SHARED_TABLES = 4096;
    public static final int MAX_SHARED_VARIANTS_PER_TABLE = 8_192;
    private static final int MAX_SHARED_RETAINED_VARIANTS = 524_288;
    private static final LongAdder TABLES = new LongAdder();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder GENERATED = new LongAdder();
    private static final LongAdder REUSED = new LongAdder();
    private static final ConcurrentHashMap<SchemaKey, String[]> SHARED_TABLES = new ConcurrentHashMap<>(512);
    private static final AtomicInteger SHARED_RETAINED_VARIANTS = new AtomicInteger();

    private final Map<List<List<String>>, List<String>> tables = new HashMap<>();
    private int retainedVariants;

    public List<String> variants(List<List<String>> options) {
        long count = 1;
        for (List<String> values : options) {
            count *= values.size();
            if (count > MAX_VARIANTS_PER_TABLE) {
                return null;
            }
        }
        if (count < 16) {
            return null;
        }
        List<List<String>> key = options.stream().map(List::copyOf).toList();
        List<String> existing = tables.get(key);
        if (existing != null) {
            HITS.increment();
            REUSED.add(existing.size());
            return existing;
        }
        if (tables.size() >= MAX_TABLES || count > MAX_RETAINED_VARIANTS - retainedVariants) {
            return null;
        }
        int[] positions = new int[key.size()];
        ArrayList<String> variants = new ArrayList<>((int) count);
        StringBuilder builder = new StringBuilder();
        for (int variant = 0; variant < count; variant++) {
            builder.setLength(0);
            for (int property = 0; property < key.size(); property++) {
                if (property != 0) {
                    builder.append(',');
                }
                builder.append(key.get(property).get(positions[property]));
            }
            variants.add(builder.toString());
            for (int property = positions.length - 1; property >= 0; property--) {
                if (++positions[property] < key.get(property).size()) {
                    break;
                }
                positions[property] = 0;
            }
        }
        List<String> result = List.copyOf(variants);
        tables.put(key, result);
        retainedVariants += result.size();
        TABLES.increment();
        GENERATED.add(result.size());
        return result;
    }

    public static long tables() { return TABLES.sum(); }
    public static long hits() { return HITS.sum(); }
    public static long generated() { return GENERATED.sum(); }
    public static long reused() { return REUSED.sum(); }

    public static String[] sharedVariants(String[][] optionArrays, int variantCount, int schemaHash) {
        if (variantCount <= 0 || variantCount > MAX_SHARED_VARIANTS_PER_TABLE) {
            return null;
        }
        SchemaKey probe = new SchemaKey(optionArrays, schemaHash);
        String[] existing = SHARED_TABLES.get(probe);
        if (existing != null) {
            HITS.increment();
            REUSED.add(existing.length);
            return existing;
        }
        if (SHARED_TABLES.size() >= MAX_SHARED_TABLES
                || variantCount > MAX_SHARED_RETAINED_VARIANTS - SHARED_RETAINED_VARIANTS.get()) {
            return null;
        }
        String[] generated = buildCartesianVariants(optionArrays, variantCount);
        SchemaKey ownedKey = new SchemaKey(optionArrays.clone(), schemaHash);
        String[] raced = SHARED_TABLES.putIfAbsent(ownedKey, generated);
        if (raced != null) {
            HITS.increment();
            REUSED.add(raced.length);
            return raced;
        }
        SHARED_RETAINED_VARIANTS.addAndGet(generated.length);
        TABLES.increment();
        GENERATED.add(generated.length);
        return generated;
    }

    private static String[] buildCartesianVariants(String[][] optionArrays, int count) {
        int propCount = optionArrays.length;
        int[] positions = new int[propCount];
        int[] startOffsets = new int[propCount];
        int[] endOffsets = new int[propCount];
        int maxLen = Math.max(0, propCount - 1);
        for (String[] opts : optionArrays) {
            int optMax = 0;
            for (String s : opts) {
                if (s.length() > optMax) {
                    optMax = s.length();
                }
            }
            maxLen += optMax;
        }
        char[] buf = new char[Math.max(16, maxLen)];
        String[] result = new String[count];

        int offset = 0;
        for (int p = 0; p < propCount; p++) {
            if (p != 0) {
                buf[offset++] = ',';
            }
            startOffsets[p] = offset;
            String s = optionArrays[p][0];
            int len = s.length();
            s.getChars(0, len, buf, offset);
            offset += len;
            endOffsets[p] = offset;
        }

        for (int i = 0; i < count; i++) {
            result[i] = new String(buf, 0, endOffsets[propCount - 1]);
            if (i + 1 >= count) {
                break;
            }
            int changedFrom = propCount - 1;
            while (changedFrom >= 0) {
                int nextPos = positions[changedFrom] + 1;
                if (nextPos < optionArrays[changedFrom].length) {
                    positions[changedFrom] = nextPos;
                    break;
                }
                positions[changedFrom] = 0;
                changedFrom--;
            }
            int writePos = startOffsets[changedFrom];
            for (int p = changedFrom; p < propCount; p++) {
                if (p != changedFrom) {
                    buf[writePos++] = ',';
                    startOffsets[p] = writePos;
                }
                String s = optionArrays[p][positions[p]];
                int len = s.length();
                s.getChars(0, len, buf, writePos);
                writePos += len;
                endOffsets[p] = writePos;
            }
        }
        return result;
    }

    private static final class SchemaKey {
        private final String[][] options;
        private final int hash;

        private SchemaKey(String[][] options, int hash) {
            this.options = options;
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof SchemaKey other)) {
                return false;
            }
            String[][] a = this.options;
            String[][] b = other.options;
            if (a.length != b.length) {
                return false;
            }
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i] && !Arrays.equals(a[i], b[i])) {
                    return false;
                }
            }
            return true;
        }
    }
}
