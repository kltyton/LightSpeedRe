package com.ccr4ft3r.lightspeed.startup.bootstrap;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.BlockStateCacheInvalidateTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.BlockStateIdMapper121Transformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.DeduplicatorTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.ModelBakeEventHelperTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.ModelLocationBuilderTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.PackResourcesCacheEngineTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.StateVariantTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft.ResourceLocationHashTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft.StitcherComparatorTransformer;
import cpw.mods.modlauncher.api.ITransformer;
import java.util.List;

/** NeoForge 1.21.1 transformation service registering pure {@code Layer.GAME} class transformers. */
public final class BootstrapTransformationService extends EarlyBootstrapService {
    @Override
    public List<? extends ITransformer<?>> transformers() {
        // Install GAME-layer bytecode optimizations without requiring a JVM agent.
        return List.of(
                ModelBakeEventHelperTransformer.forNeoForge(),
                ModelLocationBuilderTransformer.forNeoForge(),
                new ModelKeySetTransformer(),
                new BlockStateIdMapper121Transformer(),
                new StitcherComparatorTransformer(),
                new DeduplicatorTransformer(),
                new ResourceLocationHashTransformer(),
                new StateVariantTransformer(),
                new PackResourcesCacheEngineTransformer(),
                new BlockStateCacheInvalidateTransformer()
        );
    }
}
