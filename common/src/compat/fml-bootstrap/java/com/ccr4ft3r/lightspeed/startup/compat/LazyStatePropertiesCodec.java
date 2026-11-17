package com.ccr4ft3r.lightspeed.startup.compat;

import com.google.common.collect.ImmutableSortedMap;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.function.Supplier;
import java.util.stream.Stream;

/** Thread-safe lazy wrapper around the nested DFU property {@link MapCodec} chain for block states. */
public final class LazyStatePropertiesCodec<S2 extends StateHolder<?, S2>> extends MapCodec<S2> {
    private final ImmutableSortedMap<String, Property<?>> properties;
    private final Supplier<S2> defaultSupplier;
    private final StateCodecResolver resolver;
    private volatile MapCodec<S2> delegate;

    public LazyStatePropertiesCodec(
            ImmutableSortedMap<String, Property<?>> properties,
            Supplier<S2> defaultSupplier,
            StateCodecResolver resolver
    ) {
        this.properties = properties;
        this.defaultSupplier = defaultSupplier;
        this.resolver = resolver;
    }

    private MapCodec<S2> resolve() {
        MapCodec<S2> current = this.delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = this.delegate;
            if (current == null) {
                current = this.resolver.lightspeed$materializePropertiesCodec(this.properties, this.defaultSupplier);
                this.delegate = current;
            }
            return current;
        }
    }

    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) {
        return resolve().keys(ops);
    }

    @Override
    public <T> DataResult<S2> decode(DynamicOps<T> ops, MapLike<T> input) {
        return resolve().decode(ops, input);
    }

    @Override
    public <T> RecordBuilder<T> encode(S2 input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
        return resolve().encode(input, ops, prefix);
    }

    @Override
    public String toString() {
        return resolve().toString();
    }
}
