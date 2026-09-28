package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.mojang.logging.LogUtils;
import com.ccr4ft3r.lightspeed.client.model.ModelVariantTable;

public final class ClientSnapshotCoordinator {
    private ClientSnapshotCoordinator() {
    }

    public static void persistAndLog() {
        LogUtils.getLogger().info(
                "Lightspeed model variant tables: tables={} hits={} generated={} reused={}",
                ModelVariantTable.tables(), ModelVariantTable.hits(),
                ModelVariantTable.generated(), ModelVariantTable.reused());
        ModelInputSnapshot.persist();
        NativeImageSnapshot.persist();
        ShaderProgramSnapshot.persist();
        FontWarmupSnapshot.persist();
        LogUtils.getLogger().info(
                "Lightspeed client snapshots: modelHits={} modelMisses={} nativeHits={} nativeMisses={} nativeRestores={} nativeRestoreFailures={} shaderHits={} shaderMisses={} fontHits={} fontMisses={} failures={}",
                ModelInputSnapshot.hits(), ModelInputSnapshot.misses(),
                NativeImageSnapshot.hits(), NativeImageSnapshot.misses(),
                NativeImageSnapshot.restores(), NativeImageSnapshot.restoreFailures(),
                ShaderProgramSnapshot.hits(), ShaderProgramSnapshot.misses(),
                FontWarmupSnapshot.hits(), FontWarmupSnapshot.misses(),
                ModelInputSnapshot.failures() + NativeImageSnapshot.failures()
                        + ShaderProgramSnapshot.failures() + FontWarmupSnapshot.failures());
    }
}
