package com.ccr4ft3r.lightspeed.startup.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * Compact flat-array location sets that defer hash-table materialization and serve pre-sliced
 * per-namespace arrays without linear filtering overhead.
 */
public final class ModelLocationSets {
    private ModelLocationSets() {
    }

    /** Extracts namespace and owning block ID without coupling to loader mappings. */
    public interface LocationAdapter<T> {
        boolean isElement(Object obj);

        String namespace(T element);

        default Object blockId(T element) {
            return null;
        }

        default boolean isInventoryVariant(T element) {
            return false;
        }

        default boolean isStandaloneVariant(T element) {
            return false;
        }

        default boolean hasRegisteredBlockId(T element) {
            return true;
        }
    }

    /**
     * Append-only flat collector passed per worker chunk to model location builders.
     * Tracks whether every added location belongs to the active unique block ID so distinct blocks
     * skip giant chunk-wide hash tables and merge directly by array copy.
     */
    public static final class ChunkCollectorSet<T> extends AbstractSet<T> {
        private final ObjectArrayList<T> list;
        private final LocationAdapter<T> adapter;
        private ObjectOpenHashSet<T> blockSeen;
        private ObjectOpenHashSet<T> chunkSeen;
        private Object currentBlockId;
        private int currentBlockCount;
        private T currentBlockFirst;
        private boolean disjointByBlock = true;
        private boolean sawInventoryVariant;
        private boolean sawStandaloneVariant;

        public ChunkCollectorSet(int initialCapacity, LocationAdapter<T> adapter) {
            this.list = new ObjectArrayList<>(Math.max(16, initialCapacity));
            this.adapter = adapter;
        }

        public void beginBlock(Object blockId) {
            this.currentBlockId = blockId;
            this.currentBlockCount = 0;
            this.currentBlockFirst = null;
            if (blockSeen != null && !blockSeen.isEmpty()) {
                blockSeen.clear();
            }
        }

        public boolean isDisjointByBlock() {
            return disjointByBlock;
        }

        public boolean sawInventoryVariant() {
            return sawInventoryVariant;
        }

        public boolean sawStandaloneVariant() {
            return sawStandaloneVariant;
        }

        @Override
        public boolean add(T loc) {
            if (disjointByBlock) {
                Object expectedId = this.currentBlockId;
                Object actualId = expectedId == null ? null : adapter.blockId(loc);
                if (expectedId != null && expectedId.equals(actualId)) {
                    if (!sawInventoryVariant && adapter.isInventoryVariant(loc)) {
                        sawInventoryVariant = true;
                    }
                    if (!sawStandaloneVariant && adapter.isStandaloneVariant(loc)) {
                        sawStandaloneVariant = true;
                    }
                    int count = currentBlockCount;
                    if (count == 0) {
                        currentBlockFirst = loc;
                        currentBlockCount = 1;
                        list.add(loc);
                        return true;
                    }
                    if (count == 1) {
                        if (Objects.equals(currentBlockFirst, loc)) {
                            return false;
                        }
                        if (blockSeen == null) {
                            blockSeen = new ObjectOpenHashSet<>(16, 0.75f);
                        }
                        blockSeen.add(currentBlockFirst);
                        blockSeen.add(loc);
                        currentBlockCount = 2;
                        list.add(loc);
                        return true;
                    }
                    if (!blockSeen.add(loc)) {
                        return false;
                    }
                    currentBlockCount = count + 1;
                    list.add(loc);
                    return true;
                }
                promoteToChunkDedup();
            }
            if (!chunkSeen.add(loc)) {
                return false;
            }
            list.add(loc);
            return true;
        }

        private void promoteToChunkDedup() {
            disjointByBlock = false;
            int sz = list.size();
            ObjectOpenHashSet<T> seen = new ObjectOpenHashSet<>(Math.max(16, sz + 16), 0.75f);
            Object[] raw = list.elements();
            for (int i = 0; i < sz; i++) {
                @SuppressWarnings("unchecked")
                T item = (T) raw[i];
                seen.add(item);
            }
            chunkSeen = seen;
            blockSeen = null;
        }

        @Override
        public boolean contains(Object o) {
            if (!disjointByBlock) {
                return chunkSeen.contains(o);
            }
            promoteToChunkDedup();
            return chunkSeen.contains(o);
        }

        @Override
        public int size() {
            return list.size();
        }

        @Override
        public Iterator<T> iterator() {
            return new ObjectArrayIterator<>(list.elements(), list.size());
        }

        public Object[] rawElements() {
            return list.elements();
        }
    }

    /** Deduplicated insertion-ordered set that stores elements linearly and builds its hash lookup lazily on demand. */
    public static final class IndexedLocationSet<T> extends AbstractSet<T> {
        private final LocationAdapter<T> adapter;
        private volatile ObjectOpenHashSet<T> lookup;
        private volatile Map<String, CompactArrayLocationSet<T>> buckets;
        private Object[] elements;
        private int size;
        private boolean blockDisjointActive;
        private boolean disjointFromInventory;
        private boolean disjointFromStandalone;
        private ObjectOpenHashSet<T> sideLookup;

        public IndexedLocationSet(int expectedCapacity, LocationAdapter<T> adapter) {
            int cap = Math.max(16, expectedCapacity);
            this.elements = new Object[cap];
            this.adapter = adapter;
        }

        public void attachBuckets(Map<String, CompactArrayLocationSet<T>> buckets) {
            this.buckets = buckets;
        }

        public Map<String, CompactArrayLocationSet<T>> buckets() {
            return buckets;
        }

        private boolean isDisjointFromInitialBlocks(T loc) {
            if (!blockDisjointActive) {
                return false;
            }
            if (disjointFromInventory && adapter.isInventoryVariant(loc)) {
                return true;
            }
            if (disjointFromStandalone && adapter.isStandaloneVariant(loc)) {
                return true;
            }
            return !adapter.hasRegisteredBlockId(loc);
        }

        private ObjectOpenHashSet<T> ensureLookup(int expectedSize) {
            ObjectOpenHashSet<T> set = this.lookup;
            if (set == null) {
                synchronized (this) {
                    set = this.lookup;
                    if (set == null) {
                        int cap = Math.max(16, Math.max(this.size, expectedSize));
                        set = new ObjectOpenHashSet<>(cap, 0.75f);
                        Object[] arr = this.elements;
                        int limit = this.size;
                        for (int i = 0; i < limit; i++) {
                            @SuppressWarnings("unchecked")
                            T item = (T) arr[i];
                            set.add(item);
                        }
                        this.lookup = set;
                        this.blockDisjointActive = false;
                        this.disjointFromInventory = false;
                        this.disjointFromStandalone = false;
                        this.sideLookup = null;
                    }
                }
            }
            return set;
        }

        private ObjectOpenHashSet<T> ensureLookup() {
            return ensureLookup(Math.max(this.size, this.elements.length));
        }

        @Override
        public boolean add(T loc) {
            if (this.lookup == null && isDisjointFromInitialBlocks(loc)) {
                ObjectOpenHashSet<T> side = this.sideLookup;
                if (side == null) {
                    side = new ObjectOpenHashSet<>(4_096, 0.75f);
                    this.sideLookup = side;
                }
                if (!side.add(loc)) {
                    return false;
                }
                this.buckets = null;
                append(loc);
                return true;
            }
            if (!ensureLookup().add(loc)) {
                return false;
            }
            this.buckets = null;
            append(loc);
            return true;
        }

        public void appendUnchecked(T loc) {
            add(loc);
        }

        /** Bulk-appends already-disjoint chunk arrays directly when this set starts empty, avoiding rehashes. */
        public void appendDisjointChunks(ChunkCollectorSet<T>[] chunks, int totalCount) {
            if (totalCount <= 0) {
                return;
            }
            if (this.size == 0 && this.lookup == null && this.sideLookup == null) {
                ensureCapacity(totalCount);
                Object[] arr = this.elements;
                int write = 0;
                boolean anyInv = false;
                boolean anyStandalone = false;
                for (ChunkCollectorSet<T> chunk : chunks) {
                    if (chunk != null && !chunk.isEmpty()) {
                        int len = chunk.size();
                        System.arraycopy(chunk.rawElements(), 0, arr, write, len);
                        write += len;
                        if (chunk.sawInventoryVariant()) {
                            anyInv = true;
                        }
                        if (chunk.sawStandaloneVariant()) {
                            anyStandalone = true;
                        }
                    }
                }
                this.size = write;
                this.buckets = null;
                this.blockDisjointActive = write > 0;
                this.disjointFromInventory = !anyInv;
                this.disjointFromStandalone = !anyStandalone;
                return;
            }
            ObjectOpenHashSet<T> set = ensureLookup(this.size + totalCount);
            ensureCapacity(this.size + totalCount);
            Object[] arr = this.elements;
            int write = this.size;
            for (ChunkCollectorSet<T> chunk : chunks) {
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                Object[] raw = chunk.rawElements();
                int len = chunk.size();
                for (int i = 0; i < len; i++) {
                    @SuppressWarnings("unchecked")
                    T loc = (T) raw[i];
                    if (set.add(loc)) {
                        arr[write++] = loc;
                    }
                }
            }
            if (write != this.size) {
                this.buckets = null;
            }
            this.size = write;
        }

        public void appendArrayUnchecked(Object[] items, int count) {
            if (count <= 0) {
                return;
            }
            ObjectOpenHashSet<T> set = ensureLookup(Math.max(this.elements.length, this.size + count));
            ensureCapacity(size + count);
            Object[] arr = this.elements;
            int write = this.size;
            for (int i = 0; i < count; i++) {
                @SuppressWarnings("unchecked")
                T loc = (T) items[i];
                if (set.add(loc)) {
                    arr[write++] = loc;
                }
            }
            if (write != this.size) this.buckets = null;
            this.size = write;
        }

        private void append(T loc) {
            int idx = this.size;
            if (idx == elements.length) {
                ensureCapacity(idx + 1);
            }
            elements[idx] = loc;
            this.size = idx + 1;
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity <= elements.length) {
                return;
            }
            int grown = elements.length + (elements.length >> 1);
            elements = Arrays.copyOf(elements, Math.max(minCapacity, grown));
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean contains(Object o) {
            if (!adapter.isElement(o)) {
                return false;
            }
            T loc = (T) o;
            Map<String, CompactArrayLocationSet<T>> currentBuckets = this.buckets;
            if (currentBuckets != null) {
                CompactArrayLocationSet<T> slice = currentBuckets.get(adapter.namespace(loc));
                return slice != null && slice.contains(loc);
            }
            if (this.lookup == null && isDisjointFromInitialBlocks(loc)) {
                ObjectOpenHashSet<T> side = this.sideLookup;
                return side != null && side.contains(loc);
            }
            return ensureLookup().contains(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!adapter.isElement(o) || !ensureLookup().remove(o)) {
                return false;
            }
            this.buckets = null;
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(o)) {
                    int moved = size - i - 1;
                    if (moved > 0) {
                        System.arraycopy(elements, i + 1, elements, i, moved);
                    }
                    elements[--size] = null;
                    return true;
                }
            }
            return true;
        }

        @Override
        public void clear() {
            this.buckets = null;
            this.blockDisjointActive = false;
            this.disjointFromInventory = false;
            this.disjointFromStandalone = false;
            this.sideLookup = null;
            ObjectOpenHashSet<T> set = this.lookup;
            if (set != null) {
                set.clear();
            }
            Arrays.fill(elements, 0, size, null);
            size = 0;
        }

        @Override
        public int size() {
            return size;
        }

        public Object[] rawElements() {
            return elements;
        }

        @Override
        public Iterator<T> iterator() {
            return new ObjectArrayIterator<>(elements, size);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void forEach(Consumer<? super T> action) {
            Object[] arr = this.elements;
            int limit = this.size;
            for (int i = 0; i < limit; i++) {
                action.accept((T) arr[i]);
            }
        }

        @Override
        public Spliterator<T> spliterator() {
            return Spliterators.spliterator(
                    elements, 0, size,
                    Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.SIZED | Spliterator.SUBSIZED
            );
        }
    }

    /** Immutable array-backed location set that builds its hash lookup lazily on first membership probe. */
    public static final class CompactArrayLocationSet<T> extends AbstractSet<T> {
        final T[] elements;
        private final LocationAdapter<T> adapter;
        private volatile ObjectOpenHashSet<T> lookup;

        public CompactArrayLocationSet(T[] elements, LocationAdapter<T> adapter) {
            this.elements = elements;
            this.adapter = adapter;
        }

        private ObjectOpenHashSet<T> ensureLookup() {
            ObjectOpenHashSet<T> table = this.lookup;
            if (table == null) {
                synchronized (this) {
                    table = this.lookup;
                    if (table == null) {
                        table = new ObjectOpenHashSet<>(elements, 0.75f);
                        this.lookup = table;
                    }
                }
            }
            return table;
        }

        @Override
        public boolean contains(Object o) {
            if (!adapter.isElement(o)) {
                return false;
            }
            if (elements.length <= 8) {
                for (T loc : elements) {
                    if (loc.equals(o)) {
                        return true;
                    }
                }
                return false;
            }
            return ensureLookup().contains(o);
        }

        @Override
        public int size() {
            return elements.length;
        }

        @Override
        public Iterator<T> iterator() {
            return new ObjectArrayIterator<>(elements, elements.length);
        }

        @Override
        public void forEach(Consumer<? super T> action) {
            for (T loc : elements) {
                action.accept(loc);
            }
        }

        @Override
        public Spliterator<T> spliterator() {
            return Spliterators.spliterator(
                    elements, 0, elements.length,
                    Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE
                            | Spliterator.SIZED | Spliterator.SUBSIZED
            );
        }

        @Override
        public boolean add(T loc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addAll(Collection<? extends T> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ObjectArrayIterator<T> implements Iterator<T> {
        private final Object[] elements;
        private final int size;
        private int cursor;

        private ObjectArrayIterator(Object[] elements, int size) {
            this.elements = elements;
            this.size = size;
        }

        @Override
        public boolean hasNext() {
            return cursor < size;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T next() {
            int i = cursor;
            if (i >= size) {
                throw new NoSuchElementException();
            }
            cursor = i + 1;
            return (T) elements[i];
        }

        @Override
        @SuppressWarnings("unchecked")
        public void forEachRemaining(Consumer<? super T> action) {
            int i = cursor;
            int limit = size;
            cursor = limit;
            Object[] arr = elements;
            for (; i < limit; i++) {
                action.accept((T) arr[i]);
            }
        }
    }
}
