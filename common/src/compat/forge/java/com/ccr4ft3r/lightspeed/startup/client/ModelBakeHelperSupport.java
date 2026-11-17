package com.ccr4ft3r.lightspeed.startup.client;

import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.forgespi.language.IModInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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

    private static final Map<Object, BucketCache> HELPER_BUCKETS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ModelBakeHelperSupport() {
    }

    public static Set<ResourceLocation> createTopLevelLocationSet(int expected) {
        int padded = expected > 0 ? expected + Math.max(4_096, expected >> 1) : 4_096;
        return new IndexedLocationSet(padded);
    }

    public static void dispatchModifyBakingResult(
            ModList modList, BiConsumer<String, ModContainer> original) {
        List<ListenerEntry> listeners = new ArrayList<>(32);
        modList.forEachModContainer((modId, container) ->
                listeners.add(new ListenerEntry(modId, container)));
        int count = listeners.size();
        int parallelism = Math.max(1, Math.min(count, Runtime.getRuntime().availableProcessors()));
        for (List<ListenerEntry> wave : partitionModifyBakingWaves(listeners)) {
            int workers = Math.min(wave.size(), parallelism);
            if (workers <= 1) {
                for (ListenerEntry entry : wave) {
                    invokeOriginalConsumer(original, entry);
                }
                continue;
            }
            AtomicInteger cursor = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(workers - 1);
            Runnable workerTask = () -> {
                for (;;) {
                    if (failure.get() != null) return;
                    int index = cursor.getAndIncrement();
                    if (index >= wave.size()) return;
                    try {
                        invokeOriginalConsumer(original, wave.get(index));
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        return;
                    }
                }
            };
            for (int i = 1; i < workers; i++) {
                ForkJoinPool.commonPool().execute(withGameContextClassLoader(() -> {
                    try {
                        workerTask.run();
                    } finally {
                        done.countDown();
                    }
                }));
            }
            workerTask.run();
            boolean interrupted = false;
            for (;;) {
                try {
                    done.await();
                    break;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            Throwable error = failure.get();
            if (error != null) throw propagateDispatchFailure(error);
            if (interrupted) throw new IllegalStateException("Interrupted while dispatching ModelEvent.ModifyBakingResult");
        }
    }

    private static void invokeOriginalConsumer(BiConsumer<String, ModContainer> original, ListenerEntry entry) {
        ModLoadingContext context = ModLoadingContext.get();
        ModContainer previous = context.getActiveContainer();
        context.setActiveContainer(entry.container);
        try {
            original.accept(entry.modId, entry.container);
        } finally {
            context.setActiveContainer(previous);
        }
    }

    private static RuntimeException propagateDispatchFailure(Throwable error) {
        if (error instanceof RuntimeException runtime) return runtime;
        if (error instanceof Error fatal) throw fatal;
        return new RuntimeException(error);
    }

    private static List<List<ListenerEntry>> partitionModifyBakingWaves(List<ListenerEntry> listeners) {
        int count = listeners.size();
        Object2IntOpenHashMap<String> indexByModId = new Object2IntOpenHashMap<>(count);
        indexByModId.defaultReturnValue(-1);
        for (int i = 0; i < count; i++) {
            indexByModId.put(listeners.get(i).modId, i);
        }
        int[] waveIndex = new int[count];
        for (int i = 0; i < count; i++) {
            int maxDepWave = -1;
            List<? extends IModInfo.ModVersion> deps = listeners.get(i).container.getModInfo().getDependencies();
            for (int d = 0, dLen = deps.size(); d < dLen; d++) {
                String depModId = deps.get(d).getModId();
                int depIdx = indexByModId.getInt(depModId);
                if (depIdx >= 0 && depIdx < i) {
                    maxDepWave = Math.max(maxDepWave, waveIndex[depIdx]);
                }
            }
            waveIndex[i] = maxDepWave + 1;
        }
        // Also ensure later listener never collides with an earlier listener that explicitly declared a dependency on it.
        for (int i = 0; i < count; i++) {
            List<? extends IModInfo.ModVersion> deps = listeners.get(i).container.getModInfo().getDependencies();
            for (int d = 0, dLen = deps.size(); d < dLen; d++) {
                int targetIdx = indexByModId.getInt(deps.get(d).getModId());
                if (targetIdx > i && waveIndex[targetIdx] <= waveIndex[i]) {
                    waveIndex[targetIdx] = waveIndex[i] + 1;
                }
            }
        }
        int totalWaves = 1;
        for (int i = 0; i < count; i++) {
            totalWaves = Math.max(totalWaves, waveIndex[i] + 1);
        }
        List<List<ListenerEntry>> waves = new ArrayList<>(totalWaves);
        for (int w = 0; w < totalWaves; w++) {
            waves.add(new ArrayList<>());
        }
        for (int i = 0; i < count; i++) {
            waves.get(waveIndex[i]).add(listeners.get(i));
        }
        waves.removeIf(List::isEmpty);
        return waves;
    }

    private record ListenerEntry(String modId, ModContainer container) { }

    /** Only replace ModernFix's native constructor when both builder operations are available. */
    public static boolean canPopulateLocations() {
        return MODEL_LOCATION_BUILDER_CTOR != null && MODEL_LOCATION_BUILDER_GENERATE_BLOCK != null;
    }

    public static void populateLocations(
            Object helper,
            Map<ResourceLocation, BakedModel> modelRegistry,
            Set<ResourceLocation> topLevelModelLocations,
            Set<String> namespacesWithModels
    ) {
        if (!canPopulateLocations()) {
            throw new IllegalStateException("ModernFix model location builder ABI is unavailable");
        }
        if (topLevelModelLocations instanceof IndexedLocationSet indexed && indexed.isEmpty()) {
            populateIndexedLocations(helper, modelRegistry, indexed, namespacesWithModels);
            return;
        }
        if (MODEL_LOCATION_BUILDER_CTOR != null && MODEL_LOCATION_BUILDER_GENERATE_BLOCK != null) {
            populateBlocksParallel(topLevelModelLocations, namespacesWithModels);
        }
        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            topLevelModelLocations.add(new ModelResourceLocation(key, "inventory"));
            namespacesWithModels.add(key.getNamespace());
        }
        for (ResourceLocation key : modelRegistry.keySet()) {
            topLevelModelLocations.add(key);
            namespacesWithModels.add(key.getNamespace());
        }

        Map<String, ObjectArrayList<ResourceLocation>> rawBuckets = partitionByNamespace(
                null, topLevelModelLocations, false
        );
        publishBuckets(helper, topLevelModelLocations.size(), rawBuckets);
    }

    private static void populateIndexedLocations(
            Object helper,
            Map<ResourceLocation, BakedModel> modelRegistry,
            IndexedLocationSet indexed,
            Set<String> namespacesWithModels
    ) {
        boolean blocksHadInventory = false;
        if (MODEL_LOCATION_BUILDER_CTOR != null && MODEL_LOCATION_BUILDER_GENERATE_BLOCK != null) {
            blocksHadInventory = populateBlocksParallel(indexed, namespacesWithModels);
        }
        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            ModelResourceLocation itemLoc = new ModelResourceLocation(key, "inventory");
            if (blocksHadInventory) {
                indexed.add(itemLoc);
            } else {
                indexed.appendUnchecked(itemLoc);
            }
            namespacesWithModels.add(key.getNamespace());
        }

        Map<String, ObjectArrayList<ResourceLocation>> rawBuckets = partitionByNamespace(indexed, null, true);

        Map<String, ObjectOpenHashSet<ResourceLocation>> dedupByNs = null;
        for (ResourceLocation key : modelRegistry.keySet()) {
            String ns = key.getNamespace();
            ObjectArrayList<ResourceLocation> bucket = rawBuckets.get(ns);
            if (bucket == null) {
                bucket = new ObjectArrayList<>(16);
                rawBuckets.put(ns, bucket);
                bucket.add(key);
                indexed.appendUnchecked(key);
                namespacesWithModels.add(ns);
            } else {
                if (dedupByNs == null) {
                    dedupByNs = new Object2ObjectOpenHashMap<>(16);
                }
                ObjectOpenHashSet<ResourceLocation> seen = dedupByNs.get(ns);
                if (seen == null) {
                    seen = new ObjectOpenHashSet<>(bucket, 0.75f);
                    dedupByNs.put(ns, seen);
                }
                if (seen.add(key)) {
                    bucket.add(key);
                    indexed.appendUnchecked(key);
                }
            }
        }

        indexed.attachBuckets(publishBuckets(helper, indexed.size(), rawBuckets));
    }

    private static Map<String, ObjectArrayList<ResourceLocation>> partitionByNamespace(
            IndexedLocationSet indexed,
            Set<ResourceLocation> genericSet,
            boolean indexedFast
    ) {
        Map<String, ObjectArrayList<ResourceLocation>> rawBuckets = new Object2ObjectOpenHashMap<>(256);
        String lastNs = null;
        ObjectArrayList<ResourceLocation> lastBucket = null;
        if (indexedFast) {
            Object[] elements = indexed.rawElements();
            int count = indexed.size();
            for (int i = 0; i < count; i++) {
                ResourceLocation loc = (ResourceLocation) elements[i];
                String ns = loc.getNamespace();
                if (!ns.equals(lastNs)) {
                    lastBucket = rawBuckets.get(ns);
                    if (lastBucket == null) {
                        lastBucket = new ObjectArrayList<>(32);
                        rawBuckets.put(ns, lastBucket);
                    }
                    lastNs = ns;
                }
                lastBucket.add(loc);
            }
        } else {
            for (ResourceLocation loc : genericSet) {
                String ns = loc.getNamespace();
                if (!ns.equals(lastNs)) {
                    lastBucket = rawBuckets.get(ns);
                    if (lastBucket == null) {
                        lastBucket = new ObjectArrayList<>(32);
                        rawBuckets.put(ns, lastBucket);
                    }
                    lastNs = ns;
                }
                lastBucket.add(loc);
            }
        }
        return rawBuckets;
    }

    private static Map<String, CompactArrayLocationSet> publishBuckets(
            Object helper,
            int expectedTotalSize,
            Map<String, ObjectArrayList<ResourceLocation>> rawBuckets
    ) {
        Map<String, CompactArrayLocationSet> buckets = new Object2ObjectOpenHashMap<>(rawBuckets.size() * 2);
        for (Map.Entry<String, ObjectArrayList<ResourceLocation>> entry : rawBuckets.entrySet()) {
            ObjectArrayList<ResourceLocation> list = entry.getValue();
            buckets.put(entry.getKey(), new CompactArrayLocationSet(list.toArray(ResourceLocation[]::new)));
        }
        HELPER_BUCKETS.put(helper, new BucketCache(expectedTotalSize, buckets));
        return buckets;
    }

    @SuppressWarnings("unchecked")
    private static boolean populateBlocksParallel(
            Set<ResourceLocation> topLevelModelLocations,
            Set<String> namespacesWithModels
    ) {
        List<Map.Entry<ResourceKey<Block>, Block>> entries = new ArrayList<>(BuiltInRegistries.BLOCK.entrySet());
        int total = entries.size();
        if (total == 0) {
            return false;
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
                return true;
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        int chunkSize = (total + chunkCount - 1) / chunkCount;
        ChunkCollectorSet[] chunkLocs = new ChunkCollectorSet[chunkCount];
        String[][] chunkNamespaces = new String[chunkCount][];
        AtomicInteger nextChunk = new AtomicInteger(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        int poolThreads = Math.min(workers, chunkCount - 1);
        CountDownLatch latch = new CountDownLatch(poolThreads);
        Runnable worker = () -> {
            try {
                runBlockChunks(entries, total, chunkSize, chunkCount, chunkLocs, chunkNamespaces, nextChunk, failure);
            } finally {
                latch.countDown();
            }
        };

        ForkJoinPool pool = ForkJoinPool.commonPool();
        Runnable wrappedWorker = withGameContextClassLoader(worker);
        for (int i = 0; i < poolThreads; i++) {
            pool.execute(wrappedWorker);
        }
        runBlockChunks(entries, total, chunkSize, chunkCount, chunkLocs, chunkNamespaces, nextChunk, failure);

        awaitUninterruptibly(latch);

        Throwable error = failure.get();
        if (error != null) {
            throw propagate(error);
        }

        IndexedLocationSet indexedTarget = topLevelModelLocations instanceof IndexedLocationSet s ? s : null;
        boolean sawInventory = false;
        String lastNs = null;
        for (int c = 0; c < chunkCount; c++) {
            ChunkCollectorSet locs = chunkLocs[c];
            if (locs != null && !locs.isEmpty()) {
                sawInventory |= locs.sawInventoryVariant();
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
        return sawInventory;
    }

    private static void runBlockChunks(
            List<Map.Entry<ResourceKey<Block>, Block>> entries,
            int total,
            int chunkSize,
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
            int start = chunk * chunkSize;
            int end = Math.min(total, start + chunkSize);
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
            Set<ResourceLocation> topLevelModelLocations,
            Set<String> visibleNamespaces,
            Object helper
    ) {
        BucketCache cache = HELPER_BUCKETS.get(helper);
        if (cache == null || cache.expectedTotalSize != topLevelModelLocations.size()) {
            return Sets.filter(topLevelModelLocations, loc -> visibleNamespaces.contains(loc.getNamespace()));
        }
        if (visibleNamespaces.isEmpty()) {
            return Set.of();
        }
        if (visibleNamespaces.size() == 1) {
            String single = visibleNamespaces.iterator().next();
            CompactArrayLocationSet bucket = cache.buckets.get(single);
            return bucket == null ? Set.of() : bucket;
        }
        int totalLength = 0;
        for (String ns : visibleNamespaces) {
            CompactArrayLocationSet bucket = cache.buckets.get(ns);
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
            CompactArrayLocationSet bucket = cache.buckets.get(ns);
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

    private record BucketCache(int expectedTotalSize, Map<String, CompactArrayLocationSet> buckets) {
    }

    /** Append-only flat collector passed per worker chunk to {@code ModelLocationBuilder.generateForBlock}. */
    private static final class ChunkCollectorSet extends AbstractSet<ResourceLocation> {
        private final ObjectArrayList<ResourceLocation> list;
        private final ObjectOpenHashSet<ResourceLocation> seen;
        private boolean sawInventoryVariant;

        private ChunkCollectorSet(int initialCapacity) {
            this.list = new ObjectArrayList<>(Math.max(16, initialCapacity));
            this.seen = new ObjectOpenHashSet<>(Math.max(16, initialCapacity), 0.75f);
        }

        @Override
        public boolean add(ResourceLocation loc) {
            if (!seen.add(loc)) {
                return false;
            }
            if (!sawInventoryVariant
                    && loc instanceof ModelResourceLocation mrl
                    && "inventory".equals(mrl.getVariant())) {
                sawInventoryVariant = true;
            }
            list.add(loc);
            return true;
        }

        boolean sawInventoryVariant() {
            return sawInventoryVariant;
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

        void appendUnchecked(ResourceLocation loc) {
            add(loc);
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
