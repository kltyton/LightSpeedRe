package com.ccr4ft3r.lightspeed.startup.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
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

    /** Extracts a namespace and checks inventory-variant membership without coupling to loader mappings. */
    public interface LocationAdapter<T> {
        boolean isElement(Object obj);

        String namespace(T element);
    }

    /** Append-only flat collector passed per worker chunk to model location builders. */
    public static final class ChunkCollectorSet<T> extends AbstractSet<T> {
        private final ObjectArrayList<T> list;
        private final ObjectOpenHashSet<T> seen;
        private final LocationAdapter<T> adapter;

        public ChunkCollectorSet(int initialCapacity, LocationAdapter<T> adapter) {
            this.list = new ObjectArrayList<>(Math.max(16, initialCapacity));
            this.seen = new ObjectOpenHashSet<>(Math.max(16, initialCapacity), 0.75f);
            this.adapter = adapter;
        }

        @Override
        public boolean add(T loc) {
            if (!seen.add(loc)) {
                return false;
            }
            list.add(loc);
            return true;
        }

        @Override
        public boolean contains(Object o) {
            return seen.contains(o);
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

        private ObjectOpenHashSet<T> ensureLookup() {
            ObjectOpenHashSet<T> set = this.lookup;
            if (set == null) {
                synchronized (this) {
                    set = this.lookup;
                    if (set == null) {
                        set = new ObjectOpenHashSet<>(Math.max(16, size), 0.75f);
                        Object[] arr = this.elements;
                        int limit = this.size;
                        for (int i = 0; i < limit; i++) {
                            @SuppressWarnings("unchecked")
                            T item = (T) arr[i];
                            set.add(item);
                        }
                        this.lookup = set;
                    }
                }
            }
            return set;
        }

        @Override
        public boolean add(T loc) {
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

        public void appendArrayUnchecked(Object[] items, int count) {
            if (count <= 0) {
                return;
            }
            ObjectOpenHashSet<T> set = ensureLookup();
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
