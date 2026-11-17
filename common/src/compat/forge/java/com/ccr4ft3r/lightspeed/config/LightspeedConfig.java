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
        public final ForgeConfigSpec.BooleanValue installBootstrapAgent;
        public final ForgeConfigSpec.BooleanValue showStartupTime;
        public final ForgeConfigSpec.BooleanValue asyncPreloadPacks;
        public final ForgeConfigSpec.BooleanValue dedicatedResourceReloadExecutor;
        public final ForgeConfigSpec.BooleanValue parallelResourceLookup;
        public final ForgeConfigSpec.BooleanValue cacheResourceExistence;
        public final ForgeConfigSpec.BooleanValue verifyJarHash;
        public final ForgeConfigSpec.BooleanValue suppressStartupRecommendations;
        public final ForgeConfigSpec.BooleanValue isolateModdedResourceReloadFailures;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> isolatedResourceReloadListenerPatterns;

        private Common(ForgeConfigSpec.Builder builder) {
            builder.push("startup");
            showStartupTime = builder
                    .comment("Show the completed JVM-to-menu startup time on the title screen. Does not affect startup timing or log output.")
                    .define("showStartupTime", true);
            installBootstrapAgent = builder
                    .comment("Enable writing Lightspeed's -javaagent bootstrap arguments into detected launcher profiles for faster startup. Opt-in via the in-game prompt or config.")
                    .define("installBootstrapAgent", false);
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
            verifyJarHash = builder
                    .comment("Validate persisted per-mod resource caches with the full JAR SHA-256 instead of the default file identity. Reads each JAR once per launch; changing this option requires a restart and rebuilds these caches.")
                    .define("verifyJarHash", false);
            suppressStartupRecommendations = builder
                    .comment("Do not show the Java runtime recommendation after startup.")
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
            builder.pop();
        }
    }
}
