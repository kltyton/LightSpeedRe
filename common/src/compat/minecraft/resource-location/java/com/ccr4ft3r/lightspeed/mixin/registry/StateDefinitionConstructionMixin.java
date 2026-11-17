package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.compat.StatePropertyTupleList;
import com.google.common.collect.ImmutableSortedMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Enumerates state tuples without building Cartesian product lists or intermediate Streams. */
@Mixin(StateDefinition.class)
public abstract class StateDefinitionConstructionMixin {
    @Unique
    private static final int MAX_CACHED_PROPERTY_AXES = 2048;

    @Unique
    private static final ThreadLocal<IdentityHashMap<Property<?>, Comparable<?>[]>> LIGHTSPEED_AXIS_CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    @Shadow @Final private ImmutableSortedMap<String, Property<?>> propertiesByName;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;forEach(Ljava/util/function/Consumer;)V"))
    private void lightspeed$enumerateStates(Stream<List<Pair<Property<?>, Comparable<?>>>> combinations,
            Consumer<List<Pair<Property<?>, Comparable<?>>>> createState, Operation<Void> original) {
        int axisCount = propertiesByName.size();
        if (axisCount == 0) {
            createState.accept(List.of());
            return;
        }
        Property<?>[] properties = new Property<?>[axisCount];
        Comparable<?>[][] axisValues = new Comparable<?>[axisCount][];
        IdentityHashMap<Property<?>, Comparable<?>[]> cache = LIGHTSPEED_AXIS_CACHE.get();
        int axisIndex = 0;
        for (Property<?> property : propertiesByName.values()) {
            Class<?> type = property.getClass();
            if (type != BooleanProperty.class && type != IntegerProperty.class
                    && type != EnumProperty.class && type != DirectionProperty.class) {
                // Custom getters retain their native evaluation timing.
                original.call(combinations, createState);
                return;
            }
            Comparable<?>[] entries = cache.get(property);
            if (entries == null) {
                Collection<? extends Comparable<?>> possible = property.getPossibleValues();
                entries = possible.toArray(new Comparable<?>[possible.size()]);
                if (cache.size() < MAX_CACHED_PROPERTY_AXES) {
                    cache.put(property, entries);
                }
            }
            if (entries.length == 0) {
                return;
            }
            properties[axisIndex] = property;
            axisValues[axisIndex++] = entries;
        }
        Comparable<?>[] current = new Comparable<?>[axisCount];
        int[] indices = new int[axisCount];
        for (int i = 0; i < axisCount; i++) {
            current[i] = axisValues[i][0];
        }
        StatePropertyTupleList tupleView = new StatePropertyTupleList(properties, current);
        while (true) {
            createState.accept(tupleView);
            int pos = axisCount - 1;
            while (pos >= 0) {
                int next = indices[pos] + 1;
                if (next < axisValues[pos].length) {
                    indices[pos] = next;
                    current[pos] = axisValues[pos][next];
                    break;
                }
                indices[pos] = 0;
                current[pos] = axisValues[pos][0];
                pos--;
            }
            if (pos < 0) {
                break;
            }
        }
    }
}
