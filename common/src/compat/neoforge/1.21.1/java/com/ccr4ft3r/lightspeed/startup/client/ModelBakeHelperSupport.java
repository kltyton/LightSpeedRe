package com.ccr4ft3r.lightspeed.startup.client;

import com.ccr4ft3r.lightspeed.startup.client.ModelLocationSets.ChunkCollectorSet;
import com.ccr4ft3r.lightspeed.startup.client.ModelLocationSets.CompactArrayLocationSet;
import com.ccr4ft3r.lightspeed.startup.client.ModelLocationSets.IndexedLocationSet;
import com.ccr4ft3r.lightspeed.startup.client.ModelLocationSets.LocationAdapter;
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
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Runtime support for ModernFix 1.21.1 {@code ModelBakeEventHelper} and {@code ClientHooks.onModifyBakingResult}:
 * preserves ModernFix event callbacks and builds block model locations in parallel,
 * serves pre-sliced per-namespace buckets, and runs disjoint listener waves with the game TCCL bound.
 */
public final class ModelBakeHelperSupport {
    private static final LocationAdapter<ModelResourceLocation> MODEL_ADAPTER = new LocationAdapter<>() {
        @Override
        public boolean isElement(Object obj) {
            return obj instanceof ModelResourceLocation;
        }

        @Override
        public String namespace(ModelResourceLocation element) {
            return element.id().getNamespace();
        }

        @Override
        public boolean isInventoryVariant(ModelResourceLocation element) {
            return ModelResourceLocation.INVENTORY_VARIANT.equals(element.variant());
        }
    };

    private static final MethodHandle MODEL_LOCATION_BUILDER_CTOR;
    private static final MethodHandle MODEL_LOCATION_BUILDER_GENERATE_BLOCK;

    static {
        MethodHandle ctor = null;
        MethodHandle generate = null;
        try {
            Class<?> builderClass = Class.forName("org.embeddedt.modernfix.neoforge.dynresources.ModelLocationBuilder");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            ctor = lookup.findConstructor(builderClass, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
            generate = lookup.findVirtual(
                    builderClass,
                    "generateForBlock",
                    MethodType.methodType(void.class, Set.class, Block.class, ResourceLocation.class)
            ).asType(MethodType.methodType(void.class, Object.class, Set.class, Block.class, ResourceLocation.class));
        } catch (Throwable ignored) {
            // ModernFix absent at runtime or signature mismatch: callers fail open cleanly.
        }
        MODEL_LOCATION_BUILDER_CTOR = ctor;
        MODEL_LOCATION_BUILDER_GENERATE_BLOCK = generate;
    }

    private static final Map<Object, BucketCache> HELPER_BUCKETS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ModelBakeHelperSupport() {
    }

    public static Set<ModelResourceLocation> createTopLevelLocationSet(int expected) {
        int padded = expected > 0 ? expected + Math.max(4_096, expected >> 1) : 4_096;
        return new IndexedLocationSet<>(padded, MODEL_ADAPTER);
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
                int depIdx = indexByModId.getInt(deps.get(d).getModId());
                if (depIdx >= 0 && depIdx < i) {
                    maxDepWave = Math.max(maxDepWave, waveIndex[depIdx]);
                }
            }
            waveIndex[i] = maxDepWave + 1;
        }
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

    /** Only replace ModernFix's native constructor when both builder operations are available. */
    public static boolean canPopulateLocations() {
        return MODEL_LOCATION_BUILDER_CTOR != null && MODEL_LOCATION_BUILDER_GENERATE_BLOCK != null;
    }

    public static void populateLocations(
            Object helper,
            Map<ModelResourceLocation, BakedModel> modelRegistry,
            Set<ModelResourceLocation> topLevelModelLocations,
            Set<String> namespacesWithModels
    ) {
        if (!canPopulateLocations()) {
            throw new IllegalStateException("ModernFix model location builder ABI is unavailable");
        }
        if (topLevelModelLocations instanceof IndexedLocationSet<?> raw && raw.isEmpty()) {
            populateIndexedLocations(helper, modelRegistry, (IndexedLocationSet<ModelResourceLocation>) raw, namespacesWithModels);
            return;
        }
        if (MODEL_LOCATION_BUILDER_CTOR != null && MODEL_LOCATION_BUILDER_GENERATE_BLOCK != null) {
            populateBlocksParallel(topLevelModelLocations, namespacesWithModels);
        }
        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            topLevelModelLocations.add(ModelResourceLocation.inventory(key));
            namespacesWithModels.add(key.getNamespace());
        }
        for (ModelResourceLocation key : modelRegistry.keySet()) {
            topLevelModelLocations.add(key);
            namespacesWithModels.add(key.id().getNamespace());
        }

        Map<String, ObjectArrayList<ModelResourceLocation>> rawBuckets = partitionByNamespace(
                null, topLevelModelLocations, false
        );
        publishBuckets(helper, topLevelModelLocations.size(), rawBuckets);
    }

    private static void populateIndexedLocations(
            Object helper,
            Map<ModelResourceLocation, BakedModel> modelRegistry,
            IndexedLocationSet<ModelResourceLocation> indexed,
            Set<String> namespacesWithModels
    ) {
        boolean blocksHadInventory = false;
        if (MODEL_LOCATION_BUILDER_CTOR != null && MODEL_LOCATION_BUILDER_GENERATE_BLOCK != null) {
            blocksHadInventory = populateBlocksParallel(indexed, namespacesWithModels);
        }
        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            ModelResourceLocation itemLoc = ModelResourceLocation.inventory(key);
            if (blocksHadInventory) {
                indexed.add(itemLoc);
            } else {
                indexed.appendUnchecked(itemLoc);
            }
            namespacesWithModels.add(key.getNamespace());
        }

        Map<String, ObjectArrayList<ModelResourceLocation>> rawBuckets = partitionByNamespace(indexed, null, true);

        Map<String, ObjectOpenHashSet<ModelResourceLocation>> dedupByNs = null;
        for (ModelResourceLocation key : modelRegistry.keySet()) {
            String ns = key.id().getNamespace();
            ObjectArrayList<ModelResourceLocation> bucket = rawBuckets.get(ns);
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
                ObjectOpenHashSet<ModelResourceLocation> seen = dedupByNs.get(ns);
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

    private static Map<String, ObjectArrayList<ModelResourceLocation>> partitionByNamespace(
            IndexedLocationSet<ModelResourceLocation> indexed,
            Set<ModelResourceLocation> genericSet,
            boolean indexedFast
    ) {
        Map<String, ObjectArrayList<ModelResourceLocation>> rawBuckets = new Object2ObjectOpenHashMap<>(256);
        String lastNs = null;
        ObjectArrayList<ModelResourceLocation> lastBucket = null;
        if (indexedFast) {
            Object[] elements = indexed.rawElements();
            int count = indexed.size();
            for (int i = 0; i < count; i++) {
                ModelResourceLocation loc = (ModelResourceLocation) elements[i];
                String ns = loc.id().getNamespace();
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
            for (ModelResourceLocation loc : genericSet) {
                String ns = loc.id().getNamespace();
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

    private static Map<String, CompactArrayLocationSet<ModelResourceLocation>> publishBuckets(
            Object helper,
            int expectedTotalSize,
            Map<String, ObjectArrayList<ModelResourceLocation>> rawBuckets
    ) {
        Map<String, CompactArrayLocationSet<ModelResourceLocation>> buckets =
                new Object2ObjectOpenHashMap<>(rawBuckets.size() * 2);
        for (Map.Entry<String, ObjectArrayList<ModelResourceLocation>> entry : rawBuckets.entrySet()) {
            ObjectArrayList<ModelResourceLocation> list = entry.getValue();
            buckets.put(entry.getKey(),
                    new CompactArrayLocationSet<>(list.toArray(ModelResourceLocation[]::new), MODEL_ADAPTER));
        }
        if (helper != null) {
            HELPER_BUCKETS.put(helper, new BucketCache(expectedTotalSize, buckets));
        }
        return buckets;
    }

    @SuppressWarnings("unchecked")
    private static boolean populateBlocksParallel(
            Set<ModelResourceLocation> topLevelModelLocations,
            Set<String> namespacesWithModels
    ) {
        Set<Map.Entry<ResourceKey<Block>, Block>> entrySet = BuiltInRegistries.BLOCK.entrySet();
        int total = entrySet.size();
        if (total == 0) {
            return false;
        }
        List<Map.Entry<ResourceKey<Block>, Block>> entries = new ArrayList<>(entrySet);
        int workers = Math.max(1, ForkJoinPool.commonPool().getParallelism());
        int chunkCount = Math.min(total, Math.max(1, workers * 4));
        if (workers == 1 || total < 512) {
            try {
                Object builder = MODEL_LOCATION_BUILDER_CTOR.invokeExact();
                ChunkCollectorSet<ModelResourceLocation> singleSet = new ChunkCollectorSet<>(total * 2, MODEL_ADAPTER);
                String lastNs = null;
                for (int i = 0; i < total; i++) {
                    Map.Entry<ResourceKey<Block>, Block> entry = entries.get(i);
                    ResourceLocation id = entry.getKey().location();
                    MODEL_LOCATION_BUILDER_GENERATE_BLOCK.invokeExact(builder, (Set<ModelResourceLocation>) singleSet, entry.getValue(), id);
                    String ns = id.getNamespace();
                    if (!ns.equals(lastNs)) {
                        namespacesWithModels.add(ns);
                        lastNs = ns;
                    }
                }
                if (topLevelModelLocations instanceof IndexedLocationSet<?> raw) {
                    ((IndexedLocationSet<ModelResourceLocation>) raw).appendArrayUnchecked(singleSet.rawElements(), singleSet.size());
                } else {
                    topLevelModelLocations.addAll(singleSet);
                }
                return singleSet.sawInventoryVariant();
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        int chunkSize = (total + chunkCount - 1) / chunkCount;
        @SuppressWarnings("unchecked")
        ChunkCollectorSet<ModelResourceLocation>[] chunkLocs = new ChunkCollectorSet[chunkCount];
        String[][] chunkNamespaces = new String[chunkCount][];
        AtomicInteger nextChunk = new AtomicInteger(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        int poolThreads = Math.min(workers, chunkCount - 1);
        CountDownLatch latch = new CountDownLatch(poolThreads);
        Runnable worker = withGameContextClassLoader(() -> {
            try {
                runBlockChunks(entries, total, chunkSize, chunkCount, chunkLocs, chunkNamespaces, nextChunk, failure);
            } finally {
                latch.countDown();
            }
        });

        ForkJoinPool pool = ForkJoinPool.commonPool();
        for (int i = 0; i < poolThreads; i++) {
            pool.execute(worker);
        }
        runBlockChunks(entries, total, chunkSize, chunkCount, chunkLocs, chunkNamespaces, nextChunk, failure);

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

        Throwable error = failure.get();
        if (error != null) {
            throw propagate(error);
        }

        IndexedLocationSet<ModelResourceLocation> indexedTarget = topLevelModelLocations instanceof IndexedLocationSet<?> raw
                ? (IndexedLocationSet<ModelResourceLocation>) raw
                : null;
        boolean sawInventory = false;
        String lastNs = null;
        for (int c = 0; c < chunkCount; c++) {
            ChunkCollectorSet<ModelResourceLocation> locs = chunkLocs[c];
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
            ChunkCollectorSet<ModelResourceLocation>[] chunkLocs,
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
            ChunkCollectorSet<ModelResourceLocation> localSet = new ChunkCollectorSet<>((end - start) * 2, MODEL_ADAPTER);
            String[] localNs = new String[end - start];
            try {
                if (builder == null) {
                    builder = MODEL_LOCATION_BUILDER_CTOR.invokeExact();
                }
                for (int i = start; i < end; i++) {
                    Map.Entry<ResourceKey<Block>, Block> entry = entries.get(i);
                    ResourceLocation id = entry.getKey().location();
                    MODEL_LOCATION_BUILDER_GENERATE_BLOCK.invokeExact(builder, (Set<ModelResourceLocation>) localSet, entry.getValue(), id);
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

    @SuppressWarnings("unchecked")
    public static Set<ModelResourceLocation> wrapVisibleLocations(
            Set<ModelResourceLocation> topLevelModelLocations,
            Set<String> visibleNamespaces,
            Object helper
    ) {
        if (visibleNamespaces == null || visibleNamespaces.isEmpty()) {
            return Set.of();
        }
        Map<String, CompactArrayLocationSet<ModelResourceLocation>> buckets = null;
        int currentSize = topLevelModelLocations.size();
        if (topLevelModelLocations instanceof IndexedLocationSet<?> raw) {
            buckets = ((IndexedLocationSet<ModelResourceLocation>) raw).buckets();
        }
        if (buckets == null && helper != null) {
            BucketCache cached = HELPER_BUCKETS.get(helper);
            if (cached != null && cached.expectedTotalSize == currentSize) {
                buckets = cached.buckets;
            }
        }
        if (buckets == null) {
            Map<String, ObjectArrayList<ModelResourceLocation>> rawBuckets = partitionByNamespace(
                    null, topLevelModelLocations, false
            );
            buckets = publishBuckets(helper, currentSize, rawBuckets);
            if (topLevelModelLocations instanceof IndexedLocationSet<?> raw) {
                ((IndexedLocationSet<ModelResourceLocation>) raw).attachBuckets(buckets);
            }
        }
        return new NamespaceSliceSet(buckets, visibleNamespaces);
    }

    private static Runnable withGameContextClassLoader(Runnable task) {
        ClassLoader gameClassLoader = ModelBakeHelperSupport.class.getClassLoader();
        return () -> {
            Thread current = Thread.currentThread();
            ClassLoader previous = current.getContextClassLoader();
            if (previous == gameClassLoader) {
                task.run();
                return;
            }
            current.setContextClassLoader(gameClassLoader);
            try {
                task.run();
            } finally {
                current.setContextClassLoader(previous);
            }
        };
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

    private record ListenerEntry(String modId, ModContainer container) {
    }

    private record BucketCache(int expectedTotalSize, Map<String, CompactArrayLocationSet<ModelResourceLocation>> buckets) {
    }

    private static final class NamespaceSliceSet extends java.util.AbstractSet<ModelResourceLocation> {
        private final Map<String, CompactArrayLocationSet<ModelResourceLocation>> buckets;
        private final Set<String> visibleNamespaces;
        private volatile ModelResourceLocation[] flattened;

        private NamespaceSliceSet(
                Map<String, CompactArrayLocationSet<ModelResourceLocation>> buckets,
                Set<String> visibleNamespaces
        ) {
            this.buckets = buckets;
            this.visibleNamespaces = visibleNamespaces;
        }

        private ModelResourceLocation[] ensureFlattened() {
            ModelResourceLocation[] arr = this.flattened;
            if (arr != null) {
                return arr;
            }
            int total = 0;
            List<ModelResourceLocation[]> parts = new ArrayList<>(visibleNamespaces.size());
            for (String ns : visibleNamespaces) {
                CompactArrayLocationSet<ModelResourceLocation> bucket = buckets.get(ns);
                if (bucket != null && bucket.elements.length > 0) {
                    parts.add(bucket.elements);
                    total += bucket.elements.length;
                }
            }
            arr = new ModelResourceLocation[total];
            int offset = 0;
            for (ModelResourceLocation[] part : parts) {
                System.arraycopy(part, 0, arr, offset, part.length);
                offset += part.length;
            }
            this.flattened = arr;
            return arr;
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof ModelResourceLocation loc)) {
                return false;
            }
            String ns = loc.id().getNamespace();
            if (!visibleNamespaces.contains(ns)) {
                return false;
            }
            CompactArrayLocationSet<ModelResourceLocation> bucket = buckets.get(ns);
            return bucket != null && bucket.contains(loc);
        }

        @Override
        public int size() {
            ModelResourceLocation[] arr = this.flattened;
            if (arr != null) {
                return arr.length;
            }
            int total = 0;
            for (String ns : visibleNamespaces) {
                CompactArrayLocationSet<ModelResourceLocation> bucket = buckets.get(ns);
                if (bucket != null) {
                    total += bucket.elements.length;
                }
            }
            return total;
        }

        @Override
        public java.util.Iterator<ModelResourceLocation> iterator() {
            ModelResourceLocation[] arr = ensureFlattened();
            return java.util.Arrays.asList(arr).iterator();
        }
    }
}
