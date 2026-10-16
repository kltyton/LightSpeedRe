package com.ccr4ft3r.lightspeed.client.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class ModelVariantTable {
    private static final int MAX_TABLES = 1024;
    private static final int MAX_VARIANTS_PER_TABLE = 65_536;
    private static final int MAX_RETAINED_VARIANTS = 1_048_576;
    private static final LongAdder TABLES = new LongAdder();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder GENERATED = new LongAdder();
    private static final LongAdder REUSED = new LongAdder();

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
}
