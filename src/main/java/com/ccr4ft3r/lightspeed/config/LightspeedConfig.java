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
        public final ModConfigSpec.BooleanValue isolateModdedResourceReloadFailures;
        public final ModConfigSpec.ConfigValue<List<? extends String>> isolatedResourceReloadListenerPatterns;

        private Common(ModConfigSpec.Builder builder) {
            builder.push("startup");
            asyncPreloadPacks = builder
                    .comment("Preload Forge path resource packs on Lightspeed worker threads during startup.")
                    .define("asyncPreloadPacks", true);
            parallelResourceLookup = builder
                    .comment("Query candidate resource packs concurrently while preserving pack priority order for the selected result.")
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
