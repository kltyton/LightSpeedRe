package com.ccr4ft3r.lightspeed.startup.client;

import com.ccr4ft3r.lightspeed.startup.client.ModelLocationSets.ChunkCollectorSet;
import com.ccr4ft3r.lightspeed.startup.client.ModelLocationSets.CompactArrayLocationSet;
import com.ccr4ft3r.lightspeed.startup.client.ModelLocationSets.IndexedLocationSet;
import com.ccr4ft3r.lightspeed.startup.client.ModelLocationSets.LocationAdapter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        public Object blockId(ModelResourceLocation element) {
            return element.id();
        }

        @Override
        public boolean isInventoryVariant(ModelResourceLocation element) {
            return "inventory".equals(element.variant());
        }

        @Override
        public boolean isStandaloneVariant(ModelResourceLocation element) {
            return "standalone".equals(element.variant());
        }

        @Override
        public boolean hasRegisteredBlockId(ModelResourceLocation element) {
            return BuiltInRegistries.BLOCK.containsKey(element.id());
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

    private ModelBakeHelperSupport() {
    }

    public static Set<ModelResourceLocation> createTopLevelLocationSet(int expected) {
        int padded = expected > 0 ? expected + Math.max(4_096, expected >> 1) : 4_096;
        return new IndexedLocationSet<>(padded, MODEL_ADAPTER);
    }

    /** Only replace ModernFix's native constructor when both builder operations are available. */
    public static boolean canPopulateLocations() {
        return MODEL_LOCATION_BUILDER_CTOR != null && MODEL_LOCATION_BUILDER_GENERATE_BLOCK != null;
    }

    public static void populateBlockLocations(Set<ModelResourceLocation> locations, Set<String> namespaces) {
        populateBlocksParallel(locations, namespaces);
    }

    private static Map<String, CompactArrayLocationSet<ModelResourceLocation>> indexLocations(IndexedLocationSet<ModelResourceLocation> locations) {
        Map<String, ObjectArrayList<ModelResourceLocation>> grouped = new Object2ObjectOpenHashMap<>();
        for (ModelResourceLocation loc : locations) {
            grouped.computeIfAbsent(loc.id().getNamespace(), ignored -> new ObjectArrayList<>()).add(loc);
        }
        Map<String, CompactArrayLocationSet<ModelResourceLocation>> buckets = new Object2ObjectOpenHashMap<>(grouped.size());
        grouped.forEach((namespace, list) -> buckets.put(namespace, new CompactArrayLocationSet<>(list.toArray(ModelResourceLocation[]::new), MODEL_ADAPTER)));
        locations.attachBuckets(buckets);
        return buckets;
    }

    @SuppressWarnings("unchecked")
    private static void populateBlocksParallel(
            Set<ModelResourceLocation> topLevelModelLocations,
            Set<String> namespacesWithModels
    ) {
        Set<Map.Entry<ResourceKey<Block>, Block>> entrySet = BuiltInRegistries.BLOCK.entrySet();
        int total = entrySet.size();
        if (total == 0) {
            return;
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
                    singleSet.beginBlock(id);
                    MODEL_LOCATION_BUILDER_GENERATE_BLOCK.invokeExact(builder, (Set<ModelResourceLocation>) singleSet, entry.getValue(), id);
                    String ns = id.getNamespace();
                    if (!ns.equals(lastNs)) {
                        namespacesWithModels.add(ns);
                        lastNs = ns;
                    }
                }
                if (topLevelModelLocations instanceof IndexedLocationSet<?> raw) {
                    IndexedLocationSet<ModelResourceLocation> target = (IndexedLocationSet<ModelResourceLocation>) raw;
                    if (singleSet.isDisjointByBlock()) {
                        @SuppressWarnings("unchecked")
                        ChunkCollectorSet<ModelResourceLocation>[] one = new ChunkCollectorSet[]{singleSet};
                        target.appendDisjointChunks(one, singleSet.size());
                    } else {
                        target.appendArrayUnchecked(singleSet.rawElements(), singleSet.size());
                    }
                } else {
                    topLevelModelLocations.addAll(singleSet);
                }
                return;
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        @SuppressWarnings("unchecked")
        ChunkCollectorSet<ModelResourceLocation>[] chunkLocs = new ChunkCollectorSet[chunkCount];
        String[][] chunkNamespaces = new String[chunkCount][];
        AtomicInteger nextChunk = new AtomicInteger(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        int poolThreads = Math.min(workers, chunkCount - 1);
        CountDownLatch latch = new CountDownLatch(poolThreads);
        Runnable worker = withGameContextClassLoader(() -> {
            try {
                runBlockChunks(entries, total, chunkCount, chunkLocs, chunkNamespaces, nextChunk, failure);
            } finally {
                latch.countDown();
            }
        });

        ForkJoinPool pool = ForkJoinPool.commonPool();
        for (int i = 0; i < poolThreads; i++) {
            pool.execute(worker);
        }
        runBlockChunks(entries, total, chunkCount, chunkLocs, chunkNamespaces, nextChunk, failure);

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
        boolean allDisjoint = indexedTarget != null;
        int totalGenerated = 0;
        for (int c = 0; c < chunkCount; c++) {
            ChunkCollectorSet<ModelResourceLocation> locs = chunkLocs[c];
            if (locs != null) {
                totalGenerated += locs.size();
                if (!locs.isDisjointByBlock()) {
                    allDisjoint = false;
                }
            }
        }
        if (allDisjoint && totalGenerated > 0) {
            indexedTarget.appendDisjointChunks(chunkLocs, totalGenerated);
        } else {
            for (int c = 0; c < chunkCount; c++) {
                ChunkCollectorSet<ModelResourceLocation> locs = chunkLocs[c];
                if (locs != null && !locs.isEmpty()) {
                    if (indexedTarget != null) {
                        indexedTarget.appendArrayUnchecked(locs.rawElements(), locs.size());
                    } else {
                        topLevelModelLocations.addAll(locs);
                    }
                }
            }
        }
        String lastNs = null;
        for (int c = 0; c < chunkCount; c++) {
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
                int start = (int) ((long) chunk * total / chunkCount);
                int end = (int) ((long) (chunk + 1) * total / chunkCount);
            int estimatedStates = 0;
            for (int i = start; i < end; i++) {
                estimatedStates += entries.get(i).getValue().getStateDefinition().getPossibleStates().size();
            }
            ChunkCollectorSet<ModelResourceLocation> localSet = new ChunkCollectorSet<>(
                    Math.max((end - start) * 2, estimatedStates), MODEL_ADAPTER);
            String[] localNs = new String[end - start];
            try {
                if (builder == null) {
                    builder = MODEL_LOCATION_BUILDER_CTOR.invokeExact();
                }
                for (int i = start; i < end; i++) {
                    Map.Entry<ResourceKey<Block>, Block> entry = entries.get(i);
                    ResourceLocation id = entry.getKey().location();
                    localSet.beginBlock(id);
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
            Set<ModelResourceLocation> topLevelModelLocations, Set<String> visibleNamespaces) {
        if (!(topLevelModelLocations instanceof IndexedLocationSet<?> raw)) {
            return Sets.filter(topLevelModelLocations, loc -> visibleNamespaces.contains(loc.id().getNamespace()));
        }
        IndexedLocationSet<ModelResourceLocation> indexed = (IndexedLocationSet<ModelResourceLocation>) raw;
        Map<String, CompactArrayLocationSet<ModelResourceLocation>> buckets = indexed.buckets();
        if (buckets == null) {
            buckets = indexLocations(indexed);
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
