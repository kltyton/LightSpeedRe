package com.ccr4ft3r.lightspeed.startup.compat;

import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.AbstractList;

/**
 * Reusable flyweight {@link Pair} list backing a single {@code StateDefinition}'s Cartesian loop.
 * <p>Mutated in-place between iterations; callers extract all entries before advancing the loop.
 */
public final class StatePropertyTupleList extends AbstractList<Pair<Property<?>, Comparable<?>>> {
    private final Property<?>[] properties;
    private final Comparable<?>[] values;

    public StatePropertyTupleList(Property<?>[] properties, Comparable<?>[] values) {
        this.properties = properties;
        this.values = values;
    }

    @Override
    public Pair<Property<?>, Comparable<?>> get(int index) {
        return Pair.of(this.properties[index], this.values[index]);
    }

    @Override
    public int size() {
        return this.properties.length;
    }

    /** Materializes an {@link ImmutableMap} without allocating {@code Pair} streams or builders for <=10 axes. */
    public ImmutableMap<Property<?>, Comparable<?>> toImmutableMap() {
        Property<?>[] p = this.properties;
        Comparable<?>[] v = this.values;
        return switch (p.length) {
            case 0 -> ImmutableMap.of();
            case 1 -> ImmutableMap.of(p[0], v[0]);
            case 2 -> ImmutableMap.of(p[0], v[0], p[1], v[1]);
            case 3 -> ImmutableMap.of(p[0], v[0], p[1], v[1], p[2], v[2]);
            case 4 -> ImmutableMap.of(p[0], v[0], p[1], v[1], p[2], v[2], p[3], v[3]);
            case 5 -> ImmutableMap.of(p[0], v[0], p[1], v[1], p[2], v[2], p[3], v[3], p[4], v[4]);
            case 6 -> ImmutableMap.of(p[0], v[0], p[1], v[1], p[2], v[2], p[3], v[3], p[4], v[4], p[5], v[5]);
            case 7 -> ImmutableMap.of(p[0], v[0], p[1], v[1], p[2], v[2], p[3], v[3], p[4], v[4], p[5], v[5],
                    p[6], v[6]);
            case 8 -> ImmutableMap.of(p[0], v[0], p[1], v[1], p[2], v[2], p[3], v[3], p[4], v[4], p[5], v[5],
                    p[6], v[6], p[7], v[7]);
            case 9 -> ImmutableMap.of(p[0], v[0], p[1], v[1], p[2], v[2], p[3], v[3], p[4], v[4], p[5], v[5],
                    p[6], v[6], p[7], v[7], p[8], v[8]);
            case 10 -> ImmutableMap.of(p[0], v[0], p[1], v[1], p[2], v[2], p[3], v[3], p[4], v[4], p[5], v[5],
                    p[6], v[6], p[7], v[7], p[8], v[8], p[9], v[9]);
            default -> {
                ImmutableMap.Builder<Property<?>, Comparable<?>> builder = ImmutableMap.builderWithExpectedSize(p.length);
                for (int i = 0; i < p.length; i++) {
                    builder.put(p[i], v[i]);
                }
                yield builder.buildOrThrow();
            }
        };
    }
}
