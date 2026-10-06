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
        public final ModConfigSpec.BooleanValue installBootstrapAgent;
        public final ModConfigSpec.BooleanValue dedicatedResourceReloadExecutor;
        public final ModConfigSpec.BooleanValue parallelResourceLookup;
        public final ModConfigSpec.BooleanValue cacheResourceExistence;
        public final ModConfigSpec.BooleanValue verifyJarHash;
        public final ModConfigSpec.BooleanValue isolateModdedResourceReloadFailures;
        public final ModConfigSpec.ConfigValue<List<? extends String>> isolatedResourceReloadListenerPatterns;
        public final ModConfigSpec.BooleanValue connectorCompatibilityMode;

        private Common(ModConfigSpec.Builder builder) {
            builder.push("startup");
            installBootstrapAgent = builder
                    .comment("Explicitly install bootstrap Agent JVM arguments for the next launch. Disabled by default to keep exported modpacks free of machine-specific startup paths. Restart after changing this option.")
                    .define("installBootstrapAgent", false);
            dedicatedResourceReloadExecutor = builder
                    .comment("Use a dedicated work-stealing pool for resource reload preparation instead of competing for the shared Minecraft worker pool.")
                    .define("dedicatedResourceReloadExecutor", true);
            parallelResourceLookup = builder
                    .comment("Use indexed resource lookup for safe pack segments while preserving vanilla priority and filter order.")
                    .define("parallelResourceLookup", true);
            cacheResourceExistence = builder
                    .comment("Cache per-pack resource existence checks. The persisted cache is loaded lazily so it does not block startup IO.")
                    .define("cacheResourceExistence", true);
            verifyJarHash = builder
                    .comment("Validate persisted per-mod resource caches with the full JAR SHA-256 instead of the default module/version/file-name identity. Reads each JAR once per launch; changing this option requires a restart and rebuilds these caches.")
                    .define("verifyJarHash", false);
            builder.pop();

            builder.push("compatibility");
            isolateModdedResourceReloadFailures = builder
                    .comment("Complete failed third-party client resource reload listeners instead of letting one mod crash the whole loading overlay.")
                    .define("isolateModdedResourceReloadFailures", true);
            isolatedResourceReloadListenerPatterns = builder
                    .comment("Class-name prefixes whose third-party reload listener or renderer failures may be isolated. Use * for all non-core mod code.")
                    .defineList("isolatedResourceReloadListenerPatterns", List.of("*"), value -> value instanceof String string && !string.isBlank());
            connectorCompatibilityMode = builder
                    .comment("When Sinytra Connector or Continuity is installed, avoid startup/resource optimizations that change Fabric resource reload or renderer lookup timing.")
                    .define("connectorCompatibilityMode", true);
            builder.pop();
        }
    }
}
