package com.ccr4ft3r.lightspeed.bootstrap.runtime.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.atomic.LongAdder;

/** Groups the committed AT rules once instead of scanning every rule for each class. */
public final class AccessTransformerIndex {
    private static final LongAdder BUILDS = new LongAdder();
    private static final LongAdder TYPES = new LongAdder();
    private AccessTransformerIndex() { }

    public static Map<Object, List<Object>> build(Map<?, ?> rules, Function<Object, Object> targetType) {
        Map<Object, List<Object>> index = new HashMap<>();
        for (Map.Entry<?, ?> rule : rules.entrySet()) {
            index.computeIfAbsent(targetType.apply(rule.getKey()), ignored -> new ArrayList<>()).add(rule.getValue());
        }
        BUILDS.increment();
        TYPES.add(index.size());
        return index;
    }

    public static long builds() { return BUILDS.sum(); }
    public static long types() { return TYPES.sum(); }
}
