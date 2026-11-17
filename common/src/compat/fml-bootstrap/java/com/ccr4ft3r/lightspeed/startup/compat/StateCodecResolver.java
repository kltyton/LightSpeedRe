package com.ccr4ft3r.lightspeed.startup.compat;

import com.google.common.collect.ImmutableSortedMap;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.function.Supplier;

/** Bridge implemented by transformed {@code StateDefinition} to materialize deferred property codecs on demand. */
public interface StateCodecResolver {
    <S2 extends StateHolder<?, S2>> MapCodec<S2> lightspeed$materializePropertiesCodec(
            ImmutableSortedMap<String, Property<?>> properties,
            Supplier<S2> defaultSupplier
    );
}
