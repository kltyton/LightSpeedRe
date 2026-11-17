package com.ccr4ft3r.lightspeed.startup.compat;

import com.mojang.serialization.DataResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zero-allocation replacement for ResourcefulLib's {@code StateVariant.getStates(StateDefinition, String)},
 * avoiding Guava {@code Splitter}, {@code HashMap}, {@code Stream}, and {@code EntryIterator} overhead
 * while preserving exact parsing, duplicate-override, error message, and {@link DataResult} semantics.
 */
public final class StateVariantSupport {
    private StateVariantSupport() {
    }

    public static DataResult<List<BlockState>> getStates(
            StateDefinition<Block, BlockState> definition,
            String input
    ) {
        List<BlockState> possibleStates = definition.getPossibleStates();
        if (input.isEmpty()) {
            return possibleStates.isEmpty()
                    ? DataResult.error(() -> "No states found for " + input)
                    : DataResult.success(possibleStates);
        }

        int cap = 4;
        Property<?>[] props = new Property<?>[cap];
        Comparable<?>[] vals = new Comparable<?>[cap];
        int propCount = 0;

        int len = input.length();
        int segStart = 0;
        while (segStart <= len) {
            int comma = input.indexOf(',', segStart);
            int segEnd = comma < 0 ? len : comma;
            int eq = input.indexOf('=', segStart);
            String key;
            String valStr = null;
            if (eq >= 0 && eq < segEnd) {
                key = input.substring(segStart, eq);
                valStr = input.substring(eq + 1, segEnd);
            } else {
                key = input.substring(segStart, segEnd);
            }

            Property<?> property = definition.getProperty(key);
            if (property != null && valStr != null) {
                Comparable<?> parsed = property.getValue(valStr).orElse(null);
                if (parsed == null) {
                    throw new RuntimeException(
                            "Unknown value: '" + valStr + "' for blockstate property: '" + key + "' "
                                    + property.getPossibleValues()
                    );
                }
                int existingIdx = -1;
                for (int i = 0; i < propCount; i++) {
                    if (props[i] == property) {
                        existingIdx = i;
                        break;
                    }
                }
                if (existingIdx >= 0) {
                    vals[existingIdx] = parsed;
                } else {
                    if (propCount == cap) {
                        cap <<= 1;
                        Property<?>[] nextProps = new Property<?>[cap];
                        Comparable<?>[] nextVals = new Comparable<?>[cap];
                        System.arraycopy(props, 0, nextProps, 0, propCount);
                        System.arraycopy(vals, 0, nextVals, 0, propCount);
                        props = nextProps;
                        vals = nextVals;
                    }
                    props[propCount] = property;
                    vals[propCount] = parsed;
                    propCount++;
                }
            } else if (!key.isEmpty()) {
                throw new RuntimeException("Unknown blockstate property: '" + key + "'");
            }

            if (comma < 0) {
                break;
            }
            segStart = comma + 1;
        }

        if (propCount == 0) {
            return possibleStates.isEmpty()
                    ? DataResult.error(() -> "No states found for " + input)
                    : DataResult.success(possibleStates);
        }

        int totalStates = possibleStates.size();
        List<BlockState> matched = new ArrayList<>(Math.min(totalStates, 16));
        Block owner = definition.getOwner();
        for (int i = 0; i < totalStates; i++) {
            BlockState state = possibleStates.get(i);
            if (state == null || !state.is(owner)) {
                continue;
            }
            boolean allMatch = true;
            for (int p = 0; p < propCount; p++) {
                if (!Objects.equals(state.getValue(props[p]), vals[p])) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                matched.add(state);
            }
        }

        if (matched.isEmpty()) {
            return DataResult.error(() -> "No states found for " + input);
        }
        return DataResult.success(List.copyOf(matched));
    }
}
