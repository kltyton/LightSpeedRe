package com.ccr4ft3r.lightspeed.startup.compat;

import com.mojang.serialization.DataResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Direct-addressed replacement for ResourcefulLib's {@code StateVariant.getStates(StateDefinition, String)},
 * avoiding Guava {@code Splitter}, {@code HashMap}, {@code Stream}, and O(N) linear state scans when
 * targeting standard {@link StateDefinition} Cartesian state tables while preserving exact parsing,
 * duplicate-override, error message, and {@link DataResult} semantics.
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
        Block owner = definition.getOwner();

        // Fast O(propCount) direct jump when all block properties are specified on a standard StateDefinition.
        if (totalStates > 1 && definition.getClass() == StateDefinition.class) {
            Collection<Property<?>> allProps = definition.getProperties();
            if (propCount == allProps.size()) {
                BlockState direct = tryResolveFullState(definition, allProps, props, vals, propCount, totalStates, owner);
                if (direct != null) {
                    return DataResult.success(List.of(direct));
                }
            } else if (totalStates > 16) {
                List<BlockState> sliced = tryResolveCartesianSlice(possibleStates, allProps, props, vals, propCount, totalStates, owner);
                if (sliced != null) {
                    return sliced.isEmpty()
                            ? DataResult.error(() -> "No states found for " + input)
                            : DataResult.success(sliced);
                }
            }
        }

        List<BlockState> matched = new ArrayList<>(Math.min(totalStates, 16));
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

    private static BlockState tryResolveFullState(
            StateDefinition<Block, BlockState> definition,
            Collection<Property<?>> allProps,
            Property<?>[] props,
            Comparable<?>[] vals,
            int propCount,
            int totalStates,
            Block owner
    ) {
        long expectedProduct = 1L;
        for (Property<?> prop : allProps) {
            expectedProduct *= prop.getPossibleValues().size();
            if (expectedProduct > totalStates) {
                return null;
            }
        }
        if (expectedProduct != totalStates) {
            return null;
        }
        BlockState candidate = definition.any();
        if (candidate == null) {
            return null;
        }
        for (int i = 0; i < propCount; i++) {
            candidate = setValueUnchecked(candidate, props[i], vals[i]);
        }
        return candidate != null && candidate.is(owner) ? candidate : null;
    }

    private static List<BlockState> tryResolveCartesianSlice(
            List<BlockState> possibleStates,
            Collection<Property<?>> allProps,
            Property<?>[] fixedProps,
            Comparable<?>[] fixedVals,
            int propCount,
            int totalStates,
            Block owner
    ) {
        int dim = allProps.size();
        Property<?>[] ordered = new Property<?>[dim];
        int[] sizes = new int[dim];
        int[] fixedOrdinal = new int[dim];
        int idx = 0;
        long product = 1L;
        for (Property<?> p : allProps) {
            ordered[idx] = p;
            Collection<?> values = p.getPossibleValues();
            int sz = values.size();
            sizes[idx] = sz;
            product *= sz;
            if (product > totalStates) {
                return null;
            }
            int ordinal = -1;
            for (int f = 0; f < propCount; f++) {
                if (fixedProps[f] == p) {
                    ordinal = ordinalOf(values, fixedVals[f]);
                    if (ordinal < 0) {
                        return null;
                    }
                    break;
                }
            }
            fixedOrdinal[idx] = ordinal;
            idx++;
        }
        if (product != totalStates) {
            return null;
        }

        int[] strides = new int[dim];
        int stride = 1;
        for (int d = dim - 1; d >= 0; d--) {
            strides[d] = stride;
            stride *= sizes[d];
        }

        int baseIndex = 0;
        int freeCount = 0;
        int matchCount = 1;
        int[] freeSizes = new int[dim];
        int[] freeStrides = new int[dim];
        for (int d = 0; d < dim; d++) {
            if (fixedOrdinal[d] >= 0) {
                baseIndex += fixedOrdinal[d] * strides[d];
            } else {
                freeSizes[freeCount] = sizes[d];
                freeStrides[freeCount] = strides[d];
                matchCount *= sizes[d];
                freeCount++;
            }
        }

        // Verify the Cartesian ordering at baseIndex before enumerating the slice.
        BlockState sample = possibleStates.get(baseIndex);
        if (sample == null) {
            return null;
        }
        for (int f = 0; f < propCount; f++) {
            if (!Objects.equals(sample.getValue(fixedProps[f]), fixedVals[f])) {
                return null;
            }
        }
        if (matchCount > 1) {
            int lastOffset = 0;
            for (int k = 0; k < freeCount; k++) {
                lastOffset += (freeSizes[k] - 1) * freeStrides[k];
            }
            BlockState endSample = possibleStates.get(baseIndex + lastOffset);
            if (endSample == null) {
                return null;
            }
            for (int f = 0; f < propCount; f++) {
                if (!Objects.equals(endSample.getValue(fixedProps[f]), fixedVals[f])) {
                    return null;
                }
            }
        }

        List<BlockState> matched = new ArrayList<>(matchCount);
        collectCartesian(possibleStates, baseIndex, 0, freeCount, freeSizes, freeStrides, owner, matched);
        return matched.isEmpty() ? List.of() : List.copyOf(matched);
    }

    private static void collectCartesian(
            List<BlockState> possibleStates,
            int index,
            int depth,
            int freeCount,
            int[] freeSizes,
            int[] freeStrides,
            Block owner,
            List<BlockState> out
    ) {
        if (depth == freeCount) {
            BlockState state = possibleStates.get(index);
            if (state != null && state.is(owner)) {
                out.add(state);
            }
            return;
        }
        int sz = freeSizes[depth];
        int st = freeStrides[depth];
        int nextDepth = depth + 1;
        for (int i = 0; i < sz; i++) {
            collectCartesian(possibleStates, index + i * st, nextDepth, freeCount, freeSizes, freeStrides, owner, out);
        }
    }

    private static int ordinalOf(Collection<?> values, Comparable<?> target) {
        int i = 0;
        for (Object value : values) {
            if (Objects.equals(value, target)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setValueUnchecked(BlockState state, Property prop, Comparable value) {
        return state.setValue(prop, value);
    }
}
