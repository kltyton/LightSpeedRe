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
        public final ForgeConfigSpec.BooleanValue parallelResourceLookup;
        public final ForgeConfigSpec.BooleanValue isolateModdedResourceReloadFailures;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> isolatedResourceReloadListenerPatterns;

        private Common(ForgeConfigSpec.Builder builder) {
            builder.push("startup");
            asyncPreloadPacks = builder
                    .comment("Preload Forge path resource pack indexes on Lightspeed worker threads during startup.")
                    .define("asyncPreloadPacks", true);
            parallelResourceLookup = builder
                    .comment("Query safe resource-pack segments concurrently while preserving vanilla priority and filter order.")
                    .define("parallelResourceLookup", true);
            builder.pop();

            builder.push("compatibility");
            isolateModdedResourceReloadFailures = builder
                    .comment("Complete failed third-party client resource reload listeners instead of letting one mod crash the whole loading overlay.")
                    .define("isolateModdedResourceReloadFailures", true);
            isolatedResourceReloadListenerPatterns = builder
                    .comment("Class-name prefixes that may be isolated when resource reload fails. Use * for all non-core mod listeners.")
                    .defineList("isolatedResourceReloadListenerPatterns", List.of("*"), value -> value instanceof String string && !string.isBlank());
            builder.pop();
        }
    }
}
