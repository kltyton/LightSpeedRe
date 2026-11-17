package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.compat.LazyStatePropertiesCodec;
import com.ccr4ft3r.lightspeed.startup.compat.StateCodecResolver;
import com.google.common.collect.ImmutableSortedMap;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Defers eager construction of the nested DFU property {@link MapCodec} chain during block
 * {@link StateDefinition} initialization until codec serialization/deserialization is actually used.
 */
@Mixin(StateDefinition.class)
public abstract class StateDefinitionLazyCodecMixin<O, S extends StateHolder<O, S>> implements StateCodecResolver {
    @Shadow @Final private ImmutableSortedMap<String, Property<?>> propertiesByName;

    @Shadow
    private static <S2 extends StateHolder<?, S2>, T extends Comparable<T>> MapCodec<S2> appendPropertyCodec(
            MapCodec<S2> codec,
            Supplier<S2> defaultState,
            String name,
            Property<T> property
    ) {
        throw new AssertionError();
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/StateDefinition;appendPropertyCodec(Lcom/mojang/serialization/MapCodec;Ljava/util/function/Supplier;Ljava/lang/String;Lnet/minecraft/world/level/block/state/properties/Property;)Lcom/mojang/serialization/MapCodec;"
            )
    )
    private <S2 extends StateHolder<?, S2>, T extends Comparable<T>> MapCodec<S2> lightspeed$deferPropertyCodec(
            MapCodec<S2> current,
            Supplier<S2> defaultSupplier,
            String name,
            Property<T> property
    ) {
        if (current instanceof LazyStatePropertiesCodec<?>) {
            return current;
        }
        return new LazyStatePropertiesCodec<>(this.propertiesByName, defaultSupplier, this);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <S2 extends StateHolder<?, S2>> MapCodec<S2> lightspeed$materializePropertiesCodec(
            ImmutableSortedMap<String, Property<?>> properties,
            Supplier<S2> defaultSupplier
    ) {
        MapCodec<S2> codec = MapCodec.of(Encoder.empty(), Decoder.unit(defaultSupplier));
        for (Map.Entry<String, Property<?>> entry : properties.entrySet()) {
            codec = appendPropertyCodec(codec, defaultSupplier, entry.getKey(), (Property) entry.getValue());
        }
        return codec;
    }
}
