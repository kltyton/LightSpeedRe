package com.ccr4ft3r.lightspeed.bootstrap.transform;

import com.ccr4ft3r.lightspeed.bootstrap.transform.patch.ResourceLookupPatch;
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
    private static final Map<String, List<Target>> TARGETS = Map.of(
            FORGE_DISCOVERY, List.of(
                    target("fe801f95d52cff0afda4a64768a77a6567fcb8a55cbeed1efee491e2098142f3", ServiceDiscoveryPatch::forge),
                    target("b9991df65f099d651b4d07729412b4c7f6b2815e6eb1dc6b65f43a5b724b72a9", ServiceDiscoveryPatch::forge)),
            NEOFORGE_DISCOVERY, List.of(
                    target("7a94a5ce380ea983a337d5a8e3ea3eb84e88b584fc172c6ab314948967c9083c", ServiceDiscoveryPatch::neoForge)),
            SECURE_JAR, List.of(
                    target("bba6a4ee9327d364967a3cfec4707d695d5962cb42a20a4b212a26434a5b9055", ResourceLookupPatch::apply),
                    target("ce036690cdf020cafb15d4a3a84a009c6bb50cea8073ea82ada379e5778d0838", ResourceLookupPatch::apply)));

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
