package com.ccr4ft3r.lightspeed.bootstrap.transform;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.attribution.ClassDefinitionAttribution;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.transform.MixinTargetIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.transform.ClassHierarchy;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.transform.ClassPassThrough;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.registry.ObjectHolderRouter;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ClassBytesPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.BackgroundScanPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.EventBusPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.EventBusAsmLoaderPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.HttpClientConnectTimeoutPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ListenerListGrowthPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ListenerPresencePatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.EventWrapperPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ForgeScanPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.MixinOpcodeNamePatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.MixinTargetIndexPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.JdkModuleGraphPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ClassPassThroughPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ModConstructionPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ModelSetPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.KeyMappingLookupPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ModTransitionPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ModContainerPreparationPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.NamespacedWrapperCapacityPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.AccessTransformerIndexPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ObjectHolderPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ResourceLookupPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.JarModuleReaderPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.UnionInputStreamPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ServiceDiscoveryPatch;

import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public final class LauncherTransformer implements ClassFileTransformer {
    private static final String FORGE_DISCOVERY = "net/minecraftforge/fml/loading/ModDirTransformerDiscoverer";
    private static final String NEOFORGE_DISCOVERY = "net/neoforged/fml/loading/ModDirTransformerDiscoverer";
    private static final String SECURE_JAR = "cpw/mods/jarhandling/impl/Jar";
    private static final String SECURE_JAR_MODULE_READER = "cpw/mods/jarhandling/impl/Jar$JarModuleDataProvider";
    private static final String UNION_FILE_SYSTEM = "cpw/mods/niofs/union/UnionFileSystem";
    private static final String UNION_FILE_SYSTEM_PROVIDER = "cpw/mods/niofs/union/UnionFileSystemProvider";
    private static final String MODULE_CLASS_LOADER = "cpw/mods/cl/ModuleClassLoader";
    private static final String FORGE_SCANNER = "net/minecraftforge/fml/loading/moddiscovery/Scanner";
    private static final String FORGE_BACKGROUND_SCANNER =
            "net/minecraftforge/fml/loading/moddiscovery/BackgroundScanHandler";
    private static final String EVENT_BUS = "net/minecraftforge/eventbus/EventBus";
    private static final String EVENT_LISTENER_LIST = "net/minecraftforge/eventbus/ListenerList";
    private static final String EVENT_LISTENER_LIST_INST = "net/minecraftforge/eventbus/ListenerList$ListenerListInst";
    private static final String EVENT_WRAPPER_FACTORY = "net/minecraftforge/eventbus/ModLauncherFactory";
    private static final String EVENT_BUS_ASM_LOADER =
            "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader";
    private static final String OBJECT_HOLDER_REGISTRY = "net/minecraftforge/registries/ObjectHolderRegistry";
    private static final String NAMESPACED_WRAPPER = "net/minecraftforge/registries/NamespacedWrapper";
    private static final String MIXIN_BYTECODE = "org/spongepowered/asm/util/Bytecode";
    private static final String MIXIN_PROCESSOR = "org/spongepowered/asm/mixin/transformer/MixinProcessor";
    private static final String MIXIN_CONFIG = "org/spongepowered/asm/mixin/transformer/MixinConfig";
    private static final String MIXIN_EXTENSIONS = "org/spongepowered/asm/mixin/transformer/ext/Extensions";
    private static final String MIXIN_LAUNCH_PLUGIN = "org/spongepowered/asm/launch/MixinLaunchPluginLegacy";
    private static final String JDK_RESOLVER = "java/lang/module/Resolver";
    private static final String JDK_HTTP_CLIENT_IMPL = "jdk/internal/net/http/HttpClientImpl";
    private static final String CLASS_TRANSFORMER = "cpw/mods/modlauncher/ClassTransformer";
    private static final String FML_CONTAINER = "net/minecraftforge/fml/javafmlmod/FMLModContainer";
    private static final String KEY_LOOKUP = "net/minecraftforge/client/settings/KeyMappingLookup";
    private static final String MOD_CONTAINER = "net/minecraftforge/fml/ModContainer";
    private static final String MOD_LOADER = "net/minecraftforge/fml/ModLoader";
    private static final String ACCESS_TRANSFORMER_LIST = "net/minecraftforge/accesstransformer/parser/AccessTransformerList";
    private static final Map<String, List<Target>> TARGETS = Map.ofEntries(
            Map.entry(MOD_LOADER, List.of(
                    target("30f45ffd6318148c3f59994f30a0e3ee083884fb7efe313a594411b5171ed0ce",
                            ModContainerPreparationPatch::apply))),
            Map.entry(ACCESS_TRANSFORMER_LIST, List.of(
                    target("5f8b37bb4512ba5c5192474370338299e0fb15ce05cf060a546160e87cc4e6a5",
                            AccessTransformerIndexPatch::apply))),
            Map.entry(MOD_CONTAINER, List.of(
                    target("3242e1fdaf9db33edae81640288392f8a83acdb0d6ef32cacf4f6b56038a1955",
                            ModTransitionPatch::apply))),
            Map.entry(MIXIN_LAUNCH_PLUGIN, List.of(
                    target("eebe20b521483335da9fdce6b7a52a46581d6727faa207d5c3e41555badc992e",
                            MixinTargetIndexPatch::launchPlugin))),
            Map.entry(MIXIN_EXTENSIONS, List.of(
                    target("2e3e936a493a8852189bf4c0675fe522e43f59831c3be2bca877f9e1dcb701ef",
                            MixinTargetIndexPatch::generators))),
            Map.entry(KEY_LOOKUP, List.of(
                    targetWithoutAgentRead("8b09e1dfd462ada736e52d0239313d53757107fcd5f49a775543343845a25634",
                            KeyMappingLookupPatch::apply))),
            Map.entry(FML_CONTAINER, List.of(
                    targetWithoutAgentRead("01a5084c4ace7bfddb195b53f30687acb1519def63d1458da0cf64de6c0a7420",
                            ModConstructionPatch::apply))),
            Map.entry(CLASS_TRANSFORMER, List.of(
                    target("6f52d32448df4c33fa853625bd86ca09aaeb7cf07f4b14b2acbd369a3b54b50e",
                            ClassPassThroughPatch::apply))),
            Map.entry(JDK_RESOLVER, List.of(
                    targetWithoutAgentRead("50592f617814a53ad1bb78049a4d3a68bfd7fe07f4499cb881a5c13de64c0208",
                            JdkModuleGraphPatch::apply))),
            Map.entry(JDK_HTTP_CLIENT_IMPL, List.of(
                    target("8bb0617587668c0b746e1540a7f29b0547cf225355a6f922b7767e30b6188fac",
                            HttpClientConnectTimeoutPatch::apply))),
            Map.entry(MIXIN_PROCESSOR, List.of(
                    target("c354f62d30691abf138619853ece6c8f242250384ab77013fab36a3c2b6b427c",
                            MixinTargetIndexPatch::processor))),
            Map.entry(MIXIN_CONFIG, List.of(
                    target("c8e65a2dd439840f831473100adeb2f0420c0a1ae2fb1b7e5e9bea586d725ce3",
                            MixinTargetIndexPatch::configuration))),
            Map.entry(MIXIN_BYTECODE, List.of(
                    target("4e1778d03277fd62bde5fce2d65932922f8cb710926a0ecb94393f3a7d2dfb47",
                            MixinOpcodeNamePatch::apply))),
            Map.entry(FORGE_DISCOVERY, List.of(
                    target("fe801f95d52cff0afda4a64768a77a6567fcb8a55cbeed1efee491e2098142f3", ServiceDiscoveryPatch::forge),
                    target("b9991df65f099d651b4d07729412b4c7f6b2815e6eb1dc6b65f43a5b724b72a9", ServiceDiscoveryPatch::forge))),
            Map.entry(NEOFORGE_DISCOVERY, List.of(
                    target("7a94a5ce380ea983a337d5a8e3ea3eb84e88b584fc172c6ab314948967c9083c", ServiceDiscoveryPatch::neoForge))),
            Map.entry(SECURE_JAR, List.of(
                    target("bba6a4ee9327d364967a3cfec4707d695d5962cb42a20a4b212a26434a5b9055", ResourceLookupPatch::applyWithRegistration))),
            Map.entry(SECURE_JAR_MODULE_READER, List.of(
                    targetWithoutAgentRead("045c87e04bf053c21da1cd06ea9d3a2426eed5bdc3464069d0191e1758401ab0",
                            JarModuleReaderPatch::apply))),
            Map.entry(UNION_FILE_SYSTEM, List.of(
                    target("de67081ffbb73a4db7cddfbed23b397cdceea4cfe59938cf3ee16318018434c4",
                            UnionInputStreamPatch::fileSystem))),
            Map.entry(UNION_FILE_SYSTEM_PROVIDER, List.of(
                    target("4385f3c63f714548e56f6ebcad2d8b490b9b54132159040c270ab677b48c3504",
                            UnionInputStreamPatch::provider))),
            Map.entry(MODULE_CLASS_LOADER, List.of(
                    target("62e3eaa069098d55f5da70e6dbc2a35a1e622d68804b2af6049583151bcb6f16", ClassBytesPatch::apply),
                    target("1ea195fe3b32c95e7232f49c14750b9245a664e3b66c89e298c31371d8b36376", ClassBytesPatch::apply))),
            Map.entry(FORGE_SCANNER, List.of(
                    target("40475b4b77a9709ac07ef64f00aa234aad403c0f82c4e65b8329ee03f379f495", ForgeScanPatch::apply))),
            Map.entry(FORGE_BACKGROUND_SCANNER, List.of(
                    target("a6e143da3b8fe5045e61e0c9b469555a69296eff2105e4b936a47f96ba12c613",
                            BackgroundScanPatch::apply))),
            Map.entry(EVENT_BUS, List.of(
                    target("85c5db423fac7eb69107993923aa8a1967d21916fd5c9b32d36332700e31e3d0", EventBusPatch::apply))),
            Map.entry(EVENT_LISTENER_LIST, List.of(
                    target("e80d8842d05039cb6965c4acad102a879f6a3268a103f794a630c501d0f1a9de",
                            ListenerListGrowthPatch::apply))),
            Map.entry(EVENT_LISTENER_LIST_INST, List.of(
                    targetWithoutAgentRead("6e487408ade21fc340cd66ed413e9abdd750373021569f819621a9a1950b5605",
                            ListenerPresencePatch::apply))),
            Map.entry(EVENT_WRAPPER_FACTORY, List.of(
                    target("eecfddd6384bf97f6769da1e80a427bda678f093d46e3e77a27b7be696b3e46b", EventWrapperPatch::apply),
                    target("437ddbbab024eba0c41c969f74dd656cf077433fc6535264689982f211ff5676", EventWrapperPatch::apply),
                    target("3d562e4869935631d040a160b8f7c8949570eae38b23d0551c1243f0c995f38a", EventWrapperPatch::apply),
                    target("b884ef498fbdbfad4ce9b1f010993baf32ea150dc1408070086a70c638ab2806", EventWrapperPatch::apply))),
            Map.entry(EVENT_BUS_ASM_LOADER, List.of(
                    targetWithoutAgentRead("f6087d2c3e14c37ff63da95ae74dcee33f4f1983da1662f535e4c562dd0b0ec9", EventBusAsmLoaderPatch::apply),
                    targetWithoutAgentRead("4e87d4ece3377a908b5a4801b993445bc6dee8231b82ce01226feb7df8b63776", EventBusAsmLoaderPatch::apply),
                    targetWithoutAgentRead("e37ab24ca16f279c39b13c78e28ffaba8385f7bf6a94efba68e9c565ff16cb03", EventBusAsmLoaderPatch::apply))),
            Map.entry(OBJECT_HOLDER_REGISTRY, List.of(
                    target("7d3f5fb619d52c448b9f2dfccea52c676144fb4de38b0c36df0ee52cdd370ba4",
                            ObjectHolderPatch::apply))),
            Map.entry(NAMESPACED_WRAPPER, List.of(
                    targetWithoutAgentRead("63b55b2dbf81e925b56bfd4022375febbad12ac224aa5dcefe6d9b7847af3baa",
                            NamespacedWrapperCapacityPatch::apply))));

    private final Consumer<String> logger;

    public LauncherTransformer(Consumer<String> logger) {
        this.logger = logger;
        ClassDefinitionAttribution.initialize();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        return transform(null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        ClassHierarchy.observe(loader, className, classfileBuffer);
        if (classBeingRedefined == null && ClassDefinitionAttribution.enabled()) {
            ClassDefinitionAttribution.record(module, loader, className, protectionDomain);
        }
        if ("com/ccr4ft3r/lightspeed/bootstrap/AgentSmokeTarget".equals(className)) {
            logger.accept("observed Instrumentation callback for AgentSmokeTarget");
        }
        if ("net/minecraftforge/registries/RegistryObject$1".equals(className)) {
            ObjectHolderRouter.observeRegistryObject(loader, classBeingRedefined,
                    sha256(classfileBuffer).equals("0d2420035b849a0dd16d2603946eb4826fc4241d0ce16a4176c7a45ddf700ddb"));
            return null;
        }
        List<Target> candidates = TARGETS.get(className);
        if (candidates == null) {
            if (classBeingRedefined == null && className != null && className.contains("Model")
                    && !className.startsWith("com/ccr4ft3r/lightspeed/")) {
                byte[] modelSet = ModelSetPatch.apply(classfileBuffer);
                if (modelSet != null) logger.accept("optimized model collection traversal in " + className);
                return modelSet;
            }
            return null;
        }
        if (JDK_RESOLVER.equals(className) && Runtime.version().feature() != 21) {
            return null;
        }
        if (JDK_HTTP_CLIENT_IMPL.equals(className) && Runtime.version().feature() != 21) {
            return null;
        }
        if ((MIXIN_PROCESSOR.equals(className) || MIXIN_CONFIG.equals(className)
                || FML_CONTAINER.equals(className) || KEY_LOOKUP.equals(className)
                || ACCESS_TRANSFORMER_LIST.equals(className))
                && classBeingRedefined != null) {
            return null;
        }

        try {
            String fingerprint = sha256(JDK_RESOLVER.equals(className)
                    ? JdkModuleGraphPatch.methodBytes(classfileBuffer)
                    : NAMESPACED_WRAPPER.equals(className)
                    ? NamespacedWrapperCapacityPatch.methodBytes(classfileBuffer)
                    : classfileBuffer);
            Target target = null;
            for (Target candidate : candidates) {
                if (candidate.sha256().equals(fingerprint)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                logger.accept("skipped " + className + ": unsupported fingerprint " + fingerprint);
                return null;
            }
            byte[] transformed = target.patch().apply(classfileBuffer);
            if (target.requiresAgentRead()) {
                RuntimeModuleAccess.grantReadAccess(module);
            }
            if (MIXIN_CONFIG.equals(className)) {
                MixinTargetIndex.trackingInstalled(loader);
            }
            if (MIXIN_EXTENSIONS.equals(className)) {
                ClassPassThrough.generatorTrackingInstalled(loader);
            }
            if (JDK_RESOLVER.equals(className)) {
                System.setProperty("lightspeed.bootstrapAgent.moduleGraph", "true");
            }
            if (SECURE_JAR.equals(className)
                    && "bba6a4ee9327d364967a3cfec4707d695d5962cb42a20a4b212a26434a5b9055".equals(fingerprint)) {
                System.setProperty("lightspeed.bootstrapAgent.resourceIndex", "true");
            }
            logger.accept((classBeingRedefined == null ? "patched " : "repatched ")
                    + className + (JDK_RESOLVER.equals(className) || NAMESPACED_WRAPPER.equals(className)
                    ? " methodSha256=" : " sha256=") + fingerprint);
            return transformed;
        } catch (RuntimeException | LinkageError exception) {
            logger.accept("failed to patch " + className + ": " + exception);
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Target target(String sha256, UnaryOperator<byte[]> patch) {
        return new Target(sha256, patch, true);
    }

    private static Target targetWithoutAgentRead(String sha256, UnaryOperator<byte[]> patch) {
        return new Target(sha256, patch, false);
    }

    private record Target(String sha256, UnaryOperator<byte[]> patch, boolean requiresAgentRead) {
    }
}
