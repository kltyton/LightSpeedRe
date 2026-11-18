package com.ccr4ft3r.lightspeed.startup.client;

import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runtime support for ModernFix's {@code ModelBakeEventHelper} and {@code ForgeHooksClient.onModifyBakingResult}:
 * preserves ModernFix event callbacks while generating top-level model locations
 * with per-thread {@code ModelLocationBuilder} instances, stores them in a flat array-backed set for linear iteration,
 * and serves pre-sliced namespace arrays instead of linear Guava {@link Sets#filter} or linked hash scans.
 */
public final class ModelBakeHelperSupport {
    private static final MethodHandle MODEL_LOCATION_BUILDER_CTOR;
    private static final MethodHandle MODEL_LOCATION_BUILDER_GENERATE_BLOCK;

    static {
        MethodHandle ctor = null;
        MethodHandle generate = null;
        try {
            Class<?> builderClass = Class.forName("org.embeddedt.modernfix.forge.dynresources.ModelLocationBuilder");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            ctor = lookup.findConstructor(builderClass, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
            generate = lookup.findVirtual(
                    builderClass,
                    "generateForBlock",
                    MethodType.methodType(void.class, Set.class, Block.class, ResourceLocation.class)
            ).asType(MethodType.methodType(void.class, Object.class, Set.class, Block.class, ResourceLocation.class));
        } catch (Throwable ignored) {
            // ModernFix absent at runtime or signature mismatch: callers fail open gracefully.
        }
        MODEL_LOCATION_BUILDER_CTOR = ctor;
        MODEL_LOCATION_BUILDER_GENERATE_BLOCK = generate;
    }

    private ModelBakeHelperSupport() {
    }

    public static Set<ResourceLocation> createTopLevelLocationSet(int expected) {
        int padded = expected > 0 ? expected + Math.max(4_096, expected >> 1) : 4_096;
        return new IndexedLocationSet(padded);
    }

    /** Only replace ModernFix's native constructor when both builder operations are available. */
    public static boolean canPopulateLocations() {
        return MODEL_LOCATION_BUILDER_CTOR != null && MODEL_LOCATION_BUILDER_GENERATE_BLOCK != null;
    }

    public static void populateBlockLocations(Set<ResourceLocation> locations, Set<String> namespaces) {
        populateBlocksParallel(locations, namespaces);
    }

    private static Map<String, CompactArrayLocationSet> indexLocations(IndexedLocationSet locations) {
        Map<String, ObjectArrayList<ResourceLocation>> grouped = new Object2ObjectOpenHashMap<>();
        for (ResourceLocation loc : locations) {
            grouped.computeIfAbsent(loc.getNamespace(), ignored -> new ObjectArrayList<>()).add(loc);
        }
        Map<String, CompactArrayLocationSet> buckets = new Object2ObjectOpenHashMap<>(grouped.size());
        grouped.forEach((namespace, list) -> buckets.put(namespace, new CompactArrayLocationSet(list.toArray(ResourceLocation[]::new))));
        locations.attachBuckets(buckets);
        return buckets;
    }

    @SuppressWarnings("unchecked")
    private static void populateBlocksParallel(
            Set<ResourceLocation> topLevelModelLocations,
            Set<String> namespacesWithModels
    ) {
        List<Map.Entry<ResourceKey<Block>, Block>> entries = new ArrayList<>(BuiltInRegistries.BLOCK.entrySet());
        int total = entries.size();
        if (total == 0) {
            return;
        }
        int workers = Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        int chunkCount = total < 256 || workers <= 1 ? 1 : Math.min(total / 128, workers * 4);
        if (chunkCount <= 1) {
            try {
                Object builder = MODEL_LOCATION_BUILDER_CTOR.invokeExact();
                for (Map.Entry<ResourceKey<Block>, Block> entry : entries) {
                    ResourceLocation id = entry.getKey().location();
                    MODEL_LOCATION_BUILDER_GENERATE_BLOCK.invokeExact(builder, (Set<ResourceLocation>) topLevelModelLocations, entry.getValue(), id);
                    namespacesWithModels.add(id.getNamespace());
                }
                return;
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        ChunkCollectorSet[] chunkLocs = new ChunkCollectorSet[chunkCount];
        String[][] chunkNamespaces = new String[chunkCount][];
        AtomicInteger nextChunk = new AtomicInteger(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        int poolThreads = Math.min(workers, chunkCount - 1);
        CountDownLatch latch = new CountDownLatch(poolThreads);
        Runnable worker = () -> {
            try {
                runBlockChunks(entries, total, chunkCount, chunkLocs, chunkNamespaces, nextChunk, failure);
            } finally {
                latch.countDown();
            }
        };

        ForkJoinPool pool = ForkJoinPool.commonPool();
        Runnable wrappedWorker = withGameContextClassLoader(worker);
        for (int i = 0; i < poolThreads; i++) {
            pool.execute(wrappedWorker);
        }
        runBlockChunks(entries, total, chunkCount, chunkLocs, chunkNamespaces, nextChunk, failure);

        awaitUninterruptibly(latch);

        Throwable error = failure.get();
        if (error != null) {
            throw propagate(error);
        }

        IndexedLocationSet indexedTarget = topLevelModelLocations instanceof IndexedLocationSet s ? s : null;
        String lastNs = null;
        for (int c = 0; c < chunkCount; c++) {
            ChunkCollectorSet locs = chunkLocs[c];
            if (locs != null && !locs.isEmpty()) {
                if (indexedTarget != null) {
                    indexedTarget.appendArrayUnchecked(locs.rawElements(), locs.size());
                } else {
                    topLevelModelLocations.addAll(locs);
                }
            }
            String[] nss = chunkNamespaces[c];
            if (nss != null) {
                for (String ns : nss) {
                    if (!ns.equals(lastNs)) {
                        namespacesWithModels.add(ns);
                        lastNs = ns;
                    }
                }
            }
        }
    }

    private static void runBlockChunks(
            List<Map.Entry<ResourceKey<Block>, Block>> entries,
            int total,
            int chunkCount,
                ChunkCollectorSet[] chunkLocs,
                String[][] chunkNamespaces,
                AtomicInteger nextChunk,
                AtomicReference<Throwable> failure
        ) {
            Object builder = null;
            while (failure.get() == null) {
                int chunk = nextChunk.getAndIncrement();
                if (chunk >= chunkCount) {
                    return;
                }
                int start = (int) ((long) chunk * total / chunkCount);
                int end = (int) ((long) (chunk + 1) * total / chunkCount);
            ChunkCollectorSet localSet = new ChunkCollectorSet((end - start) * 2);
            String[] localNs = new String[end - start];
            try {
                if (builder == null) {
                    builder = MODEL_LOCATION_BUILDER_CTOR.invokeExact();
                }
                for (int i = start; i < end; i++) {
                    Map.Entry<ResourceKey<Block>, Block> entry = entries.get(i);
                    ResourceLocation id = entry.getKey().location();
                    MODEL_LOCATION_BUILDER_GENERATE_BLOCK.invokeExact(builder, (Set<ResourceLocation>) localSet, entry.getValue(), id);
                    localNs[i - start] = id.getNamespace();
                }
                chunkLocs[chunk] = localSet;
                chunkNamespaces[chunk] = localNs;
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                return;
            }
        }
    }

    public static Set<ResourceLocation> wrapVisibleLocations(
            Set<ResourceLocation> topLevelModelLocations, Set<String> visibleNamespaces) {
        if (!(topLevelModelLocations instanceof IndexedLocationSet indexed)) {
            return Sets.filter(topLevelModelLocations, loc -> visibleNamespaces.contains(loc.getNamespace()));
        }
        Map<String, CompactArrayLocationSet> buckets = indexed.buckets;
        if (buckets == null) {
            buckets = indexLocations(indexed);
        }
        if (visibleNamespaces.isEmpty()) {
            return Set.of();
        }
        if (visibleNamespaces.size() == 1) {
            String single = visibleNamespaces.iterator().next();
            CompactArrayLocationSet bucket = buckets.get(single);
            return bucket == null ? Set.of() : bucket;
        }
        int totalLength = 0;
        for (String ns : visibleNamespaces) {
            CompactArrayLocationSet bucket = buckets.get(ns);
            if (bucket != null) {
                totalLength += bucket.elements.length;
            }
        }
        if (totalLength == 0) {
            return Set.of();
        }
        ResourceLocation[] merged = new ResourceLocation[totalLength];
        int offset = 0;
        for (String ns : visibleNamespaces) {
            CompactArrayLocationSet bucket = buckets.get(ns);
            if (bucket != null && bucket.elements.length > 0) {
                System.arraycopy(bucket.elements, 0, merged, offset, bucket.elements.length);
                offset += bucket.elements.length;
            }
        }
        return new CompactArrayLocationSet(merged);
    }

    private static Runnable withGameContextClassLoader(Runnable task) {
        ClassLoader gameClassLoader = ModelBakeHelperSupport.class.getClassLoader();
        return () -> {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            if (previous == gameClassLoader) {
                task.run();
                return;
            }
            thread.setContextClassLoader(gameClassLoader);
            try {
                task.run();
            } finally {
                thread.setContextClassLoader(previous);
            }
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException propagate(Throwable t) {
        if (t instanceof RuntimeException runtime) {
            return runtime;
        }
        if (t instanceof Error err) {
            throw err;
        }
        return new RuntimeException(t);
    }

    /** Append-only flat collector passed per worker chunk to {@code ModelLocationBuilder.generateForBlock}. */
    private static final class ChunkCollectorSet extends AbstractSet<ResourceLocation> {
        private final ObjectArrayList<ResourceLocation> list;
        private final ObjectOpenHashSet<ResourceLocation> seen;

        private ChunkCollectorSet(int initialCapacity) {
            this.list = new ObjectArrayList<>(Math.max(16, initialCapacity));
            this.seen = new ObjectOpenHashSet<>(Math.max(16, initialCapacity), 0.75f);
        }

        @Override
        public boolean add(ResourceLocation loc) {
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
        public Iterator<ResourceLocation> iterator() {
            return new ObjectArrayIterator(list.elements(), list.size());
        }

        Object[] rawElements() {
            return list.elements();
        }
    }

    /** Deduplicated insertion-ordered set that stores elements linearly and builds its hash lookup lazily on demand. */
    private static final class IndexedLocationSet extends AbstractSet<ResourceLocation> {
        private volatile ObjectOpenHashSet<ResourceLocation> lookup;
        private volatile Map<String, CompactArrayLocationSet> buckets;
        private Object[] elements;
        private int size;

        void attachBuckets(Map<String, CompactArrayLocationSet> buckets) {
            this.buckets = buckets;
        }

        private IndexedLocationSet(int expectedCapacity) {
            int cap = Math.max(16, expectedCapacity);
            this.elements = new Object[cap];
        }

        private ObjectOpenHashSet<ResourceLocation> ensureLookup() {
            ObjectOpenHashSet<ResourceLocation> set = this.lookup;
            if (set == null) {
                synchronized (this) {
                    set = this.lookup;
                    if (set == null) {
                        set = new ObjectOpenHashSet<>(Math.max(16, size), 0.75f);
                        Object[] arr = this.elements;
                        int limit = this.size;
                        for (int i = 0; i < limit; i++) {
                            set.add((ResourceLocation) arr[i]);
                        }
                        this.lookup = set;
                    }
                }
            }
            return set;
        }

        @Override
        public boolean add(ResourceLocation loc) {
            if (!ensureLookup().add(loc)) {
                return false;
            }
            this.buckets = null;
            append(loc);
            return true;
        }

        void appendArrayUnchecked(Object[] items, int count) {
            if (count <= 0) {
                return;
            }
            ObjectOpenHashSet<ResourceLocation> set = ensureLookup();
            ensureCapacity(size + count);
            Object[] arr = this.elements;
            int write = this.size;
            for (int i = 0; i < count; i++) {
                ResourceLocation loc = (ResourceLocation) items[i];
                if (set.add(loc)) {
                    arr[write++] = loc;
                }
            }
            if (write != this.size) this.buckets = null;
            this.size = write;
        }

        private void append(ResourceLocation loc) {
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
        public boolean contains(Object o) {
            if (!(o instanceof ResourceLocation loc)) {
                return false;
            }
            Map<String, CompactArrayLocationSet> currentBuckets = this.buckets;
            if (currentBuckets != null) {
                CompactArrayLocationSet slice = currentBuckets.get(loc.getNamespace());
                return slice != null && slice.contains(loc);
            }
            return ensureLookup().contains(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!(o instanceof ResourceLocation)) {
                return false;
            }
            if (!ensureLookup().remove(o)) {
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
            ObjectOpenHashSet<ResourceLocation> set = this.lookup;
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

        Object[] rawElements() {
            return elements;
        }

        @Override
        public Iterator<ResourceLocation> iterator() {
            return new ObjectArrayIterator(elements, size);
        }

        @Override
        public void forEach(Consumer<? super ResourceLocation> action) {
            Object[] arr = this.elements;
            int limit = this.size;
            for (int i = 0; i < limit; i++) {
                action.accept((ResourceLocation) arr[i]);
            }
        }

        @Override
        public Spliterator<ResourceLocation> spliterator() {
            return Spliterators.spliterator(
                    elements, 0, size,
                    Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.SIZED | Spliterator.SUBSIZED
            );
        }
    }

    /** Immutable array-backed location set that builds its hash lookup lazily on first membership probe. */
    private static final class CompactArrayLocationSet extends AbstractSet<ResourceLocation> {
        final ResourceLocation[] elements;
        private volatile ObjectOpenHashSet<ResourceLocation> lookup;

        private CompactArrayLocationSet(ResourceLocation[] elements) {
            this.elements = elements;
        }

        private ObjectOpenHashSet<ResourceLocation> ensureLookup() {
            ObjectOpenHashSet<ResourceLocation> table = this.lookup;
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
            if (!(o instanceof ResourceLocation)) {
                return false;
            }
            if (elements.length <= 8) {
                for (ResourceLocation loc : elements) {
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
        public Iterator<ResourceLocation> iterator() {
            return new ObjectArrayIterator(elements, elements.length);
        }

        @Override
        public void forEach(Consumer<? super ResourceLocation> action) {
            for (ResourceLocation loc : elements) {
                action.accept(loc);
            }
        }

        @Override
        public Spliterator<ResourceLocation> spliterator() {
            return Spliterators.spliterator(
                    elements, 0, elements.length,
                    Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE
                            | Spliterator.SIZED | Spliterator.SUBSIZED
            );
        }

        @Override
        public boolean add(ResourceLocation loc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addAll(Collection<? extends ResourceLocation> c) {
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

    private static final class ObjectArrayIterator implements Iterator<ResourceLocation> {
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
        public ResourceLocation next() {
            int i = cursor;
            if (i >= size) {
                throw new NoSuchElementException();
            }
            cursor = i + 1;
            return (ResourceLocation) elements[i];
        }

        @Override
        public void forEachRemaining(Consumer<? super ResourceLocation> action) {
            int i = cursor;
            int limit = size;
            cursor = limit;
            Object[] arr = elements;
            for (; i < limit; i++) {
                action.accept((ResourceLocation) arr[i]);
            }
        }
    }
}
