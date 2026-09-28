package com.ccr4ft3r.lightspeed.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

public final class LightspeedConfig {
    public static final ForgeConfigSpec SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        SPEC = pair.getRight();
    }

    private LightspeedConfig() {
    }

    public static final class Common {
        public final ForgeConfigSpec.BooleanValue asyncPreloadPacks;
        public final ForgeConfigSpec.BooleanValue dedicatedResourceReloadExecutor;
        public final ForgeConfigSpec.BooleanValue parallelResourceLookup;
        public final ForgeConfigSpec.BooleanValue cacheResourceExistence;
        public final ForgeConfigSpec.BooleanValue suppressStartupRecommendations;
        public final ForgeConfigSpec.BooleanValue isolateModdedResourceReloadFailures;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> isolatedResourceReloadListenerPatterns;
        public final ForgeConfigSpec.BooleanValue connectorCompatibilityMode;

        private Common(ForgeConfigSpec.Builder builder) {
            builder.push("startup");
            asyncPreloadPacks = builder
                    .comment("Preload Forge path resource pack indexes on Lightspeed worker threads during startup.")
                    .define("asyncPreloadPacks", true);
            dedicatedResourceReloadExecutor = builder
                    .comment("Use Lightspeed's bounded startup pool for resource reload preparation; manual reloads after the title screen use Minecraft's live executor.")
                    .define("dedicatedResourceReloadExecutor", true);
            parallelResourceLookup = builder
                    .comment("Use indexed resource lookup for safe pack segments while preserving vanilla priority and filter order.")
                    .define("parallelResourceLookup", true);
            cacheResourceExistence = builder
                    .comment("Cache per-pack resource existence checks. The persisted cache is loaded lazily so it does not block startup IO.")
                    .define("cacheResourceExistence", true);
            suppressStartupRecommendations = builder
                    .comment("Do not show Lightspeed's bootstrap Agent recommendation on startup.")
                    .define("suppressStartupRecommendations", false);
            builder.pop();

            builder.push("compatibility");
            isolateModdedResourceReloadFailures = builder
                    .comment("Opt in to isolating failures owned by explicitly configured third-party resource reload listener classes.")
                    .define("isolateModdedResourceReloadFailures", false);
            isolatedResourceReloadListenerPatterns = builder
                    .comment("Explicit class-name prefixes that may be isolated when resource reload fails. Wildcards are rejected so unrelated failures remain visible.")
                    .defineList("isolatedResourceReloadListenerPatterns", List.of(),
                            value -> value instanceof String string && !string.isBlank() && !"*".equals(string));
            connectorCompatibilityMode = builder
                    .comment("When Sinytra Connector is installed, avoid startup/resource optimizations that change Fabric resource reload or renderer lookup timing.")
                    .define("connectorCompatibilityMode", true);
            builder.pop();
        }
    }
}
