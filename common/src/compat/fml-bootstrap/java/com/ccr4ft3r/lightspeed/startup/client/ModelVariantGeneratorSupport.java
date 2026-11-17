package com.ccr4ft3r.lightspeed.startup.client;

import com.ccr4ft3r.lightspeed.client.model.ModelVariantTable;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates multi-property blockstate {@link ModelResourceLocation} entries without
 * {@code Lists.cartesianProduct}, intermediate {@code ArrayList}s, or {@code StringBuilder}s.
 * Canonical per-property string pairs are shared lock-free across worker threads via identity
 * keys, and repeated property schemas reuse pre-formatted arrays from {@link ModelVariantTable}.
 */
public final class ModelVariantGeneratorSupport {
    private static final int SCRATCH_MAX_PROPS = 16;

    private static final ConcurrentHashMap<PropertyIdentityKey, PropertyOptions> GLOBAL_PROPERTY_OPTIONS =
            new ConcurrentHashMap<>(512);
    private static final ThreadLocal<ThreadScratch> THREAD_SCRATCH =
            ThreadLocal.withInitial(ThreadScratch::new);

    private ModelVariantGeneratorSupport() {
    }

    public static void generateMultiProperty(
            Set<? super ModelResourceLocation> target,
            ResourceLocation blockId,
            Collection<Property<?>> props
    ) {
        int propCount = props.size();
        if (propCount <= 0) {
            target.add(new ModelResourceLocation(blockId, ""));
            return;
        }
        ThreadScratch scratch = THREAD_SCRATCH.get();
        String[][] optionArrays = propCount <= SCRATCH_MAX_PROPS
                ? scratch.optionBuffers[propCount]
                : new String[propCount][];

        long count = 1L;
        int schemaHash = 1;
        int index = 0;
        boolean overflow = false;
        IdentityHashMap<Property<?>, PropertyOptions> localProps = scratch.localPropertyOptions;
        for (Property<?> prop : props) {
            PropertyOptions entry = getPropertyOptions(localProps, prop);
            String[] pairs = entry.pairs;
            if (pairs.length == 0) {
                return;
            }
            optionArrays[index++] = pairs;
            schemaHash = 31 * schemaHash + entry.pairsHash;
            if (!overflow) {
                if (count > Long.MAX_VALUE / pairs.length) {
                    overflow = true;
                } else {
                    count *= pairs.length;
                }
            }
        }

        if (!overflow && count <= ModelVariantTable.MAX_SHARED_VARIANTS_PER_TABLE) {
            String[] cached = ModelVariantTable.sharedVariants(optionArrays, (int) count, schemaHash);
            if (cached != null) {
                for (int i = 0, len = cached.length; i < len; i++) {
                    target.add(new ModelResourceLocation(blockId, cached[i]));
                }
                return;
            }
        }

        streamCartesianDirect(target, blockId, optionArrays, scratch);
    }

    private static PropertyOptions getPropertyOptions(
            IdentityHashMap<Property<?>, PropertyOptions> localProps,
            Property<?> prop
    ) {
        PropertyOptions cached = localProps.get(prop);
        if (cached != null) {
            return cached;
        }
        PropertyIdentityKey key = new PropertyIdentityKey(prop);
        PropertyOptions entry = GLOBAL_PROPERTY_OPTIONS.get(key);
        if (entry == null) {
            PropertyOptions computed = buildPropertyOptions(prop);
            PropertyOptions existing = GLOBAL_PROPERTY_OPTIONS.putIfAbsent(key, computed);
            entry = existing != null ? existing : computed;
        }
        localProps.put(prop, entry);
        return entry;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PropertyOptions buildPropertyOptions(Property<?> prop) {
        Collection<?> values = prop.getPossibleValues();
        String prefix = prop.getName().toLowerCase(Locale.ROOT) + "=";
        String[] pairs = new String[values.size()];
        int hash = 1;
        int i = 0;
        for (Object value : values) {
            String formatted = prefix + ((Property) prop).getName((Comparable) value).toLowerCase(Locale.ROOT);
            pairs[i++] = formatted;
            hash = 31 * hash + formatted.hashCode();
        }
        return new PropertyOptions(pairs, hash);
    }

    private static void streamCartesianDirect(
            Set<? super ModelResourceLocation> target,
            ResourceLocation blockId,
            String[][] optionArrays,
            ThreadScratch scratch
    ) {
        int propCount = optionArrays.length;
        int[] positions;
        int[] startOffsets;
        int[] endOffsets;
        if (propCount <= SCRATCH_MAX_PROPS) {
            positions = scratch.positions;
            startOffsets = scratch.startOffsets;
            endOffsets = scratch.endOffsets;
        } else {
            positions = new int[propCount];
            startOffsets = new int[propCount];
            endOffsets = new int[propCount];
        }

        int maxLen = Math.max(0, propCount - 1);
        for (int p = 0; p < propCount; p++) {
            positions[p] = 0;
            int optMax = 0;
            for (String s : optionArrays[p]) {
                if (s.length() > optMax) {
                    optMax = s.length();
                }
            }
            maxLen += optMax;
        }
        char[] buf = scratch.ensureCharBuffer(maxLen);

        int offset = 0;
        for (int p = 0; p < propCount; p++) {
            if (p != 0) {
                buf[offset++] = ',';
            }
            startOffsets[p] = offset;
            String s = optionArrays[p][0];
            int len = s.length();
            s.getChars(0, len, buf, offset);
            offset += len;
            endOffsets[p] = offset;
        }

        while (true) {
            target.add(new ModelResourceLocation(blockId, new String(buf, 0, endOffsets[propCount - 1])));

            int changedFrom = propCount - 1;
            while (changedFrom >= 0) {
                int nextPos = positions[changedFrom] + 1;
                if (nextPos < optionArrays[changedFrom].length) {
                    positions[changedFrom] = nextPos;
                    break;
                }
                positions[changedFrom] = 0;
                changedFrom--;
            }
            if (changedFrom < 0) {
                break;
            }
            int writePos = startOffsets[changedFrom];
            for (int p = changedFrom; p < propCount; p++) {
                if (p != changedFrom) {
                    buf[writePos++] = ',';
                    startOffsets[p] = writePos;
                }
                String s = optionArrays[p][positions[p]];
                int len = s.length();
                s.getChars(0, len, buf, writePos);
                writePos += len;
                endOffsets[p] = writePos;
            }
        }
    }

    private record PropertyOptions(String[] pairs, int pairsHash) {
    }

    private static final class PropertyIdentityKey {
        private final Property<?> property;
        private final int identityHash;

        private PropertyIdentityKey(Property<?> property) {
            this.property = property;
            this.identityHash = System.identityHashCode(property);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof PropertyIdentityKey other && this.property == other.property;
        }
    }

    private static final class ThreadScratch {
        private final IdentityHashMap<Property<?>, PropertyOptions> localPropertyOptions =
                new IdentityHashMap<>(256);
        private final String[][][] optionBuffers = new String[SCRATCH_MAX_PROPS + 1][][];
        private final int[] positions = new int[SCRATCH_MAX_PROPS];
        private final int[] startOffsets = new int[SCRATCH_MAX_PROPS];
        private final int[] endOffsets = new int[SCRATCH_MAX_PROPS];
        private char[] charBuffer = new char[256];

        private ThreadScratch() {
            for (int i = 2; i <= SCRATCH_MAX_PROPS; i++) {
                optionBuffers[i] = new String[i][];
            }
        }

        private char[] ensureCharBuffer(int required) {
            if (charBuffer.length < required) {
                charBuffer = new char[Math.max(required, charBuffer.length * 2)];
            }
            return charBuffer;
        }
    }
}
