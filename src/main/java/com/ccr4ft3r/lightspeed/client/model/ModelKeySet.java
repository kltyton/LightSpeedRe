package com.ccr4ft3r.lightspeed.client.model;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;

import java.util.ConcurrentModificationException;
import java.util.BitSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;

/** Keeps insertion order while reusing a dense traversal of large model key sets. */
public final class ModelKeySet<K> extends ObjectLinkedOpenHashSet<K> {
    private transient Object[] traversal;
    private transient boolean traversedOnce;
    private transient int revision;

    public ModelKeySet() { super(); }
    public ModelKeySet(int expected) { super(expected); }
    public ModelKeySet(int expected, float loadFactor) { super(expected, loadFactor); }

    private void changed() { traversal = null; traversedOnce = false; revision++; }

    @Override public boolean add(K value) {
        boolean added = super.add(value);
        if (added) changed();
        return added;
    }

    @Override public K addOrGet(K value) {
        int before = size();
        K result = super.addOrGet(value);
        if (before != size()) changed();
        return result;
    }

    @Override public boolean remove(Object value) {
        boolean removed = super.remove(value);
        if (removed) changed();
        return removed;
    }

    @Override public K removeFirst() { K value = super.removeFirst(); changed(); return value; }
    @Override public K removeLast() { K value = super.removeLast(); changed(); return value; }
    @Override public boolean addAndMoveToFirst(K value) {
        boolean changesOrder = isEmpty() || !Objects.equals(first(), value);
        boolean added = super.addAndMoveToFirst(value);
        if (changesOrder) changed();
        return added;
    }
    @Override public boolean addAndMoveToLast(K value) {
        boolean changesOrder = isEmpty() || !Objects.equals(last(), value);
        boolean added = super.addAndMoveToLast(value);
        if (changesOrder) changed();
        return added;
    }
    @Override public void clear() { if (!isEmpty()) { super.clear(); changed(); } }

    @Override public ObjectListIterator<K> iterator() {
        if (traversal == null) {
            if (!traversedOnce) {
                traversedOnce = true;
                return new DirectTraversal(super.iterator());
            }
            Object[] values = new Object[size()];
            ObjectListIterator<K> source = super.iterator();
            int index = 0;
            while (source.hasNext()) values[index++] = source.next();
            traversal = values;
        }
        return new Traversal(traversal);
    }

    @Override public ObjectListIterator<K> iterator(K from) {
        ObjectListIterator<K> source = super.iterator(from);
        return new ObjectListIterator<>() {
            public boolean hasNext() { return source.hasNext(); }
            public boolean hasPrevious() { return source.hasPrevious(); }
            public K next() { return source.next(); }
            public K previous() { return source.previous(); }
            public int nextIndex() { return source.nextIndex(); }
            public int previousIndex() { return source.previousIndex(); }
            public void remove() { source.remove(); changed(); }
        };
    }

    @Override public void forEach(Consumer<? super K> action) { iterator().forEachRemaining(action); }

    private final class DirectTraversal implements ObjectListIterator<K> {
        private final ObjectListIterator<K> source;
        private int expectedRevision = revision;

        private DirectTraversal(ObjectListIterator<K> source) { this.source = source; }
        private void checkRevision() { if (revision != expectedRevision) throw new ConcurrentModificationException(); }
        public boolean hasNext() { return source.hasNext(); }
        public boolean hasPrevious() { return source.hasPrevious(); }
        public K next() { checkRevision(); return source.next(); }
        public K previous() { checkRevision(); return source.previous(); }
        public int nextIndex() { return source.nextIndex(); }
        public int previousIndex() { return source.previousIndex(); }
        public void remove() {
            checkRevision();
            source.remove();
            changed();
            expectedRevision = revision;
        }
    }

    private final class Traversal implements ObjectListIterator<K> {
        private final Object[] values;
        private BitSet removed;
        private int expectedRevision = revision;
        private int cursor;
        private int index;
        private int last = -1;

        private Traversal(Object[] values) { this.values = values; }
        private void checkRevision() { if (revision != expectedRevision) throw new ConcurrentModificationException(); }
        private int nextPosition() { return removed == null ? cursor : removed.nextClearBit(cursor); }
        private int previousPosition() { return removed == null ? cursor - 1 : removed.previousClearBit(cursor - 1); }
        public boolean hasNext() { return nextPosition() < values.length; }
        public boolean hasPrevious() { return previousPosition() >= 0; }
        public int nextIndex() { return index; }
        public int previousIndex() { return index - 1; }

        @SuppressWarnings("unchecked")
        public K next() {
            checkRevision();
            int position = nextPosition();
            if (position >= values.length) throw new NoSuchElementException();
            last = position;
            cursor = position + 1;
            index++;
            return (K) values[last];
        }

        @SuppressWarnings("unchecked")
        public K previous() {
            checkRevision();
            int position = previousPosition();
            if (position < 0) throw new NoSuchElementException();
            last = position;
            cursor = position;
            index--;
            return (K) values[last];
        }

        public void remove() {
            checkRevision();
            if (last < 0) throw new IllegalStateException();
            ModelKeySet.this.remove(values[last]);
            if (removed == null) removed = new BitSet(values.length);
            removed.set(last);
            if (last < cursor) index--;
            expectedRevision = revision;
            last = -1;
        }
    }
}
