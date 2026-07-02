package com.ccr4ft3r.lightspeed.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

public final class LightspeedConfig {
    public static final ModConfigSpec SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        SPEC = pair.getRight();
    }

    private LightspeedConfig() {
    }

    public static final class Common {
        public final ModConfigSpec.BooleanValue asyncPreloadPacks;
        public final ModConfigSpec.BooleanValue parallelResourceLookup;
        public final ModConfigSpec.IntValue parallelLookupMinPacks;
        public final ModConfigSpec.BooleanValue cacheResourceExistence;
        public final ModConfigSpec.BooleanValue isolateModdedResourceReloadFailures;
        public final ModConfigSpec.ConfigValue<List<? extends String>> isolatedResourceReloadListenerPatterns;
        public final ModConfigSpec.BooleanValue connectorCompatibilityMode;

        private Common(ModConfigSpec.Builder builder) {
            builder.push("startup");
            asyncPreloadPacks = builder
                    .comment("Preload NeoForge path resource pack indexes on Lightspeed worker threads during startup.")
                    .define("asyncPreloadPacks", true);
            parallelResourceLookup = builder
                    .comment("Query safe resource-pack segments concurrently while preserving vanilla priority and filter order.")
                    .define("parallelResourceLookup", true);
            parallelLookupMinPacks = builder
                    .comment("Minimum safe pack segment size before Lightspeed uses parallel resource lookup. Small segments are faster sequentially.")
                    .defineInRange("parallelLookupMinPacks", 4, 2, 64);
            cacheResourceExistence = builder
                    .comment("Cache per-pack resource existence checks. The persisted cache is loaded lazily so it does not block startup IO.")
                    .define("cacheResourceExistence", true);
            builder.pop();

            builder.push("compatibility");
            isolateModdedResourceReloadFailures = builder
                    .comment("Complete failed third-party client resource reload listeners instead of letting one mod crash the whole loading overlay.")
                    .define("isolateModdedResourceReloadFailures", true);
            isolatedResourceReloadListenerPatterns = builder
                    .comment("Class-name prefixes that may be isolated when resource reload fails. Use * for all non-core mod listeners.")
                    .defineList("isolatedResourceReloadListenerPatterns", List.of("*"), value -> value instanceof String string && !string.isBlank());
            connectorCompatibilityMode = builder
                    .comment("When Sinytra Connector is installed, avoid startup/resource optimizations that change Fabric resource reload or renderer lookup timing.")
                    .define("connectorCompatibilityMode", true);
            builder.pop();
        }
    }
}
