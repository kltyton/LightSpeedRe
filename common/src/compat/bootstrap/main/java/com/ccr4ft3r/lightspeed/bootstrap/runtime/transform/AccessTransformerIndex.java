package com.ccr4ft3r.lightspeed.bootstrap.runtime.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentHashMap;

/** Groups the committed AT rules once instead of scanning every rule for each class. */
public final class AccessTransformerIndex {
    private static final LongAdder BUILDS = new LongAdder();
    private static final LongAdder TYPES = new LongAdder();
    private static final Map<Object, Map<Object, List<Object>>> INDEXES = new ConcurrentHashMap<>();
    private static final Map<Object, Map<Object, Map<Object, Map<String, Object>>>> NAMED_INDEXES = new ConcurrentHashMap<>();
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

    public static Map<Object, Map<Object, Map<String, Object>>> buildNamed(
            Map<?, ?> rules,
            Function<Object, Object> targetAsmType,
            Function<Object, Object> targetType,
            Function<Object, String> targetName) {
        Map<Object, Map<Object, Map<String, Object>>> index = new HashMap<>(Math.max(16, rules.size()));
        for (Map.Entry<?, ?> rule : rules.entrySet()) {
            Object key = rule.getKey();
            Object asmType = targetAsmType.apply(key);
            Object type = targetType.apply(key);
            String name = targetName.apply(key);
            index.computeIfAbsent(asmType, ignored -> new HashMap<>(4))
                    .computeIfAbsent(type, ignored -> new HashMap<>(4))
                    .put(name, rule.getValue());
        }
        BUILDS.increment();
        TYPES.add(index.size());
        return index;
    }

    public static Map<Object, List<Object>> forOwner(Object owner, Map<?, ?> rules,
                                                    Function<Object, Object> targetType) {
        return INDEXES.computeIfAbsent(owner, ignored -> build(rules, targetType));
    }

    public static Map<Object, Map<Object, Map<String, Object>>> forOwnerNamed(
            Object owner,
            Map<?, ?> rules,
            Function<Object, Object> targetAsmType,
            Function<Object, Object> targetType,
            Function<Object, String> targetName) {
        return NAMED_INDEXES.computeIfAbsent(owner, ignored -> buildNamed(rules, targetAsmType, targetType, targetName));
    }

    public static void invalidate(Object owner) {
        INDEXES.remove(owner);
        NAMED_INDEXES.remove(owner);
    }

    public static long builds() { return BUILDS.sum(); }
    public static long types() { return TYPES.sum(); }
}
