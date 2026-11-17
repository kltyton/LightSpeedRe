package com.ccr4ft3r.lightspeed.startup.compat;

import com.mojang.datafixers.util.Pair;
import java.util.List;
import com.google.common.collect.ImmutableMap;

/**
 * Allocates property-value {@link ImmutableMap} instances for block/fluid states directly
 * from the enumerated pair list without per-state {@link java.util.stream.Stream} pipelines.
 */
public final class StateDefinitionSupport {
    private StateDefinitionSupport() {
    }

    @SuppressWarnings("unchecked")
    public static <K, V> ImmutableMap<K, V> toPropertyMap(List<Pair<K, V>> pairs) {
        if ((Object) pairs instanceof StatePropertyTupleList tupleView) {
            return (ImmutableMap<K, V>) tupleView.toImmutableMap();
        }
        int size = pairs.size();
        return switch (size) {
            case 0 -> ImmutableMap.of();
            case 1 -> {
                Pair<K, V> p0 = pairs.get(0);
                yield ImmutableMap.of(p0.getFirst(), p0.getSecond());
            }
            case 2 -> {
                Pair<K, V> p0 = pairs.get(0), p1 = pairs.get(1);
                yield ImmutableMap.of(p0.getFirst(), p0.getSecond(), p1.getFirst(), p1.getSecond());
            }
            case 3 -> {
                Pair<K, V> p0 = pairs.get(0), p1 = pairs.get(1), p2 = pairs.get(2);
                yield ImmutableMap.of(p0.getFirst(), p0.getSecond(), p1.getFirst(), p1.getSecond(),
                        p2.getFirst(), p2.getSecond());
            }
            case 4 -> {
                Pair<K, V> p0 = pairs.get(0), p1 = pairs.get(1), p2 = pairs.get(2), p3 = pairs.get(3);
                yield ImmutableMap.of(p0.getFirst(), p0.getSecond(), p1.getFirst(), p1.getSecond(),
                        p2.getFirst(), p2.getSecond(), p3.getFirst(), p3.getSecond());
            }
            default -> {
                ImmutableMap.Builder<K, V> builder = ImmutableMap.builderWithExpectedSize(size);
                for (int i = 0; i < size; i++) {
                    Pair<K, V> pair = pairs.get(i);
                    builder.put(pair.getFirst(), pair.getSecond());
                }
                yield builder.build();
            }
        };
    }
}
