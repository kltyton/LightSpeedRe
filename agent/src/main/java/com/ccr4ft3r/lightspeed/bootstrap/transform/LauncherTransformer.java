package com.ccr4ft3r.lightspeed.bootstrap.transform;

import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ResourceLookupPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ClassBytesPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.EventBusPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.EventWrapperPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ForgeScanPatch;
import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ModuleLayerPatch;
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
    private static final String MODULE_CLASS_LOADER = "cpw/mods/cl/ModuleClassLoader";
    private static final String FORGE_SCANNER = "net/minecraftforge/fml/loading/moddiscovery/Scanner";
    private static final String EVENT_BUS = "net/minecraftforge/eventbus/EventBus";
    private static final String EVENT_WRAPPER_FACTORY = "net/minecraftforge/eventbus/ModLauncherFactory";
    private static final String MODULE_LAYER_HANDLER = "cpw/mods/modlauncher/ModuleLayerHandler";
    private static final Map<String, List<Target>> TARGETS = Map.of(
            FORGE_DISCOVERY, List.of(
                    target("fe801f95d52cff0afda4a64768a77a6567fcb8a55cbeed1efee491e2098142f3", ServiceDiscoveryPatch::forge),
                    target("b9991df65f099d651b4d07729412b4c7f6b2815e6eb1dc6b65f43a5b724b72a9", ServiceDiscoveryPatch::forge)),
            NEOFORGE_DISCOVERY, List.of(
                    target("7a94a5ce380ea983a337d5a8e3ea3eb84e88b584fc172c6ab314948967c9083c", ServiceDiscoveryPatch::neoForge)),
            SECURE_JAR, List.of(
                    target("bba6a4ee9327d364967a3cfec4707d695d5962cb42a20a4b212a26434a5b9055", ResourceLookupPatch::applyWithRegistration),
                    target("ce036690cdf020cafb15d4a3a84a009c6bb50cea8073ea82ada379e5778d0838", ResourceLookupPatch::apply)),
            MODULE_CLASS_LOADER, List.of(
                    target("62e3eaa069098d55f5da70e6dbc2a35a1e622d68804b2af6049583151bcb6f16", ClassBytesPatch::apply)),
            FORGE_SCANNER, List.of(
                    target("40475b4b77a9709ac07ef64f00aa234aad403c0f82c4e65b8329ee03f379f495", ForgeScanPatch::apply)),
            EVENT_BUS, List.of(
                    target("85c5db423fac7eb69107993923aa8a1967d21916fd5c9b32d36332700e31e3d0", EventBusPatch::apply)),
            EVENT_WRAPPER_FACTORY, List.of(
                    target("eecfddd6384bf97f6769da1e80a427bda678f093d46e3e77a27b7be696b3e46b", EventWrapperPatch::apply),
                    target("437ddbbab024eba0c41c969f74dd656cf077433fc6535264689982f211ff5676", EventWrapperPatch::apply),
                    target("3d562e4869935631d040a160b8f7c8949570eae38b23d0551c1243f0c995f38a", EventWrapperPatch::apply),
                    target("b884ef498fbdbfad4ce9b1f010993baf32ea150dc1408070086a70c638ab2806", EventWrapperPatch::apply)),
            MODULE_LAYER_HANDLER, List.of(
                    target("b8095ee7008211f12d8b0c4db4f01fb49eb5f76ce1bb6d41b4e736cfc9b8394f", ModuleLayerPatch::apply),
                    target("7d7a7736322c9489df70c93bbe2469007468f91c296c927a4319f3fd3a146558", ModuleLayerPatch::apply),
                    target("c6cd537240737843f9cf13d26736cc9301d6ba6a452565bc6dc7bb90d210f9b4", ModuleLayerPatch::apply)));

    private final Consumer<String> logger;

    public LauncherTransformer(Consumer<String> logger) {
        this.logger = logger;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        return transform(null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if ("com/ccr4ft3r/lightspeed/bootstrap/AgentSmokeTarget".equals(className)) {
            logger.accept("observed Instrumentation callback for AgentSmokeTarget");
        }
        List<Target> candidates = TARGETS.get(className);
        if (candidates == null || classBeingRedefined != null) {
            return null;
        }

        try {
            String fingerprint = sha256(classfileBuffer);
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
            if (SECURE_JAR.equals(className)
                    && "bba6a4ee9327d364967a3cfec4707d695d5962cb42a20a4b212a26434a5b9055".equals(fingerprint)) {
                System.setProperty("lightspeed.bootstrapAgent.resourceIndex", "true");
            }
            logger.accept("patched " + className + " sha256=" + fingerprint);
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
        return new Target(sha256, patch);
    }

    private record Target(String sha256, UnaryOperator<byte[]> patch) {
    }
}
