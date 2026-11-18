package com.ccr4ft3r.lightspeed.startup.bootstrap;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.CompatClassTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.DeduplicatorTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.FastMapTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.ModWorkManagerQueueTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.ModelBakeEventHelperTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.BlockStateCacheHandlerTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.ModelLocationBuilderTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft.StateDefinitionTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft.StitcherComparatorTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.OneTimeEventReceiverTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.StateVariantTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat.PackResourcesCacheEngineTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.loading.LoadingQueueTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.loading.ModContainerPreparationTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft.BlockStateIdMapperTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge.BiomeFieldRedirects;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge.ForgeRegistrySlaveCacheTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge.ForgeRegistrySyncTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge.NamespacedWrapperCapacityTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge.PathPackResourcesTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft.ModelResourceLocationHashTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft.ResourceLocationHashTransformer;
import cpw.mods.modlauncher.api.ITransformer;
import net.minecraftforge.fml.loading.FMLLoader;

import java.util.List;
import java.util.stream.Stream;

/**
 * Forge ModLauncher transformation service registering pure {@code Layer.GAME} class transformers
 * without loading any JVM agent.
 */
public final class BootstrapTransformationService extends EarlyBootstrapService {
    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        Stream<ITransformer> registered = Stream.of(
                new LoadingQueueTransformer(),
                new ModContainerPreparationTransformer(),
                new ModWorkManagerQueueTransformer(),
                new ModelBakeEventHelperTransformer(),
                new BlockStateCacheHandlerTransformer(),
                new ModelLocationBuilderTransformer(),
                new StateDefinitionTransformer(),
                new StitcherComparatorTransformer(),
                new DeduplicatorTransformer(),
                new FastMapTransformer(),
                new ResourceLocationHashTransformer(),
                new ModelResourceLocationHashTransformer(),
                new PackResourcesCacheEngineTransformer(),
                new PathPackResourcesTransformer(),
                new OneTimeEventReceiverTransformer(),
                new BlockStateIdMapperTransformer(),
                new NamespacedWrapperCapacityTransformer(),
                new ForgeRegistrySlaveCacheTransformer(),
                new ForgeRegistrySyncTransformer(),
                new StateVariantTransformer()
        );
        if ("47.4.28".equals(FMLLoader.versionInfo().forgeVersion())) {
            BiomeFieldRedirects redirects = new BiomeFieldRedirects();
            registered = Stream.concat(registered, Stream.of(redirects.prepare(), redirects.restore()));
        }
        return registered.map(CompatClassTransformer::ensureDirect).toList();
    }
}
