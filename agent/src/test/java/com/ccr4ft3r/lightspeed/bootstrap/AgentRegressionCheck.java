package com.ccr4ft3r.lightspeed.bootstrap;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks;
import com.ccr4ft3r.lightspeed.bootstrap.transform.LauncherTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class AgentRegressionCheck {
    private static final String HOOK_OWNER = "com/ccr4ft3r/lightspeed/bootstrap/runtime/BootstrapHooks";

    private AgentRegressionCheck() {
    }

    public static void main(String[] args) throws Exception {
        transformsSupportedLauncherClasses();
        rejectsUnknownClassFingerprint();
        servicePrefilterIsFailOpen();
        resourceIndexHasNoFalseNegatives();
        packagedAgentIsIsolated();
    }

    private static void transformsSupportedLauncherClasses() throws Exception {
        List<String> messages = new ArrayList<>();
        LauncherTransformer transformer = new LauncherTransformer(messages::add);
        for (Target target : targets()) {
            byte[] original = readEntry(target.jar(), target.entry());
            require(sha256(original).equals(target.sha256()), "unexpected target bytes for " + target.className());
            byte[] transformed = transformer.transform(null, null, target.className(), null, null, original);
            require(transformed != null, "supported target was not transformed: " + target.className());
            require(!MessageDigest.isEqual(original, transformed), "transform returned unchanged bytes: " + target.className());
            require(hasHookCall(transformed, target.hookName()), "transformed class lacks hook " + target.hookName());
            new ClassReader(transformed).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
        require(messages.stream().noneMatch(message -> message.contains("failed")), "supported transform logged failure");
    }

    private static void rejectsUnknownClassFingerprint() throws Exception {
        Target target = targets().get(0);
        byte[] changed = readEntry(target.jar(), target.entry());
        changed[changed.length - 1] ^= 1;
        List<String> messages = new ArrayList<>();
        byte[] transformed = new LauncherTransformer(messages::add)
                .transform(null, null, target.className(), null, null, changed);
        require(transformed == null, "unknown target fingerprint was transformed");
        require(messages.stream().anyMatch(message -> message.contains("unsupported fingerprint")),
                "unknown target fingerprint was not reported");
    }

    private static void servicePrefilterIsFailOpen() throws IOException {
        Path directory = Files.createTempDirectory("lightspeed-agent-service-");
        try {
            Path ordinary = jar(directory.resolve("ordinary.jar"), Map.of("assets/example.txt", new byte[]{1}), false);
            Path service = jar(directory.resolve("service.jar"),
                    Map.of("META-INF/services/cpw.mods.modlauncher.api.ITransformationService", new byte[]{1}), false);
            Path module = jar(directory.resolve("module.jar"), Map.of("module-info.class", new byte[]{1}), false);
            Path multiRelease = jar(directory.resolve("multi-release.jar"), Map.of("assets/example.txt", new byte[]{1}), true);
            Path invalid = directory.resolve("invalid.jar");
            Files.writeString(invalid, "not a zip");

            require(!BootstrapHooks.mayProvideTransformerService(ordinary), "ordinary jar was not rejected");
            require(BootstrapHooks.mayProvideTransformerService(service), "service jar was rejected");
            require(BootstrapHooks.mayProvideTransformerService(module), "module jar was rejected");
            require(BootstrapHooks.mayProvideTransformerService(multiRelease), "multi-release jar did not fail open");
            require(BootstrapHooks.mayProvideTransformerService(invalid), "invalid jar did not fail open");
        } finally {
            deleteTree(directory);
        }
    }

    private static void resourceIndexHasNoFalseNegatives() throws IOException {
        Path directory = Files.createTempDirectory("lightspeed-agent-index-");
        try {
            Path archive = jar(directory.resolve("resources.jar"), Map.of(
                    "present.txt", new byte[]{1},
                    "nested/also-present.txt", new byte[]{2}), false);
            try (FileSystem zip = FileSystems.newFileSystem(archive)) {
                Path root = zip.getPath("/");
                require(BootstrapHooks.mightContain(root, archive, "present.txt"), "present resource was rejected");
                require(BootstrapHooks.mightContain(root, archive, "nested/also-present.txt"), "nested resource was rejected");
                require(!BootstrapHooks.mightContain(root, archive, "missing.txt"), "missing resource was not rejected");
            }

            Path multiRelease = jar(directory.resolve("multi-release.jar"),
                    Map.of("META-INF/versions/17/only-versioned.txt", new byte[]{1}), true);
            try (FileSystem zip = FileSystems.newFileSystem(multiRelease)) {
                require(BootstrapHooks.mightContain(zip.getPath("/"), multiRelease, "only-versioned.txt"),
                        "multi-release index did not fail open");
            }
            require(BootstrapHooks.mightContain(directory, directory, "anything"), "mutable directory did not fail open");
        } finally {
            deleteTree(directory);
        }
    }

    private static void packagedAgentIsIsolated() throws IOException {
        Path agent = Path.of(requireProperty("lightspeed.agent.jar"));
        try (JarFile jar = new JarFile(agent.toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            require("com.ccr4ft3r.lightspeed.bootstrap.LightspeedAgent".equals(attributes.getValue("Premain-Class")),
                    "agent manifest lacks Premain-Class");
            require(jar.getEntry("com/ccr4ft3r/lightspeed/bootstrap/runtime/BootstrapHooks.class") != null,
                    "agent runtime hook is missing");
            require(jar.getEntry("com/ccr4ft3r/lightspeed/bootstrap/internal/asm/ClassReader.class") != null,
                    "ASM was not relocated");
            require(jar.getEntry("org/objectweb/asm/ClassReader.class") == null, "unrelocated ASM leaked into agent jar");
            require(jar.getEntry("META-INF/mods.toml") == null && jar.getEntry("META-INF/neoforge.mods.toml") == null,
                    "agent jar must not be discovered as a mod");
        }
    }

    private static List<Target> targets() {
        return List.of(
                target("lightspeed.target.forge.production", "net/minecraftforge/fml/loading/ModDirTransformerDiscoverer",
                        "fe801f95d52cff0afda4a64768a77a6567fcb8a55cbeed1efee491e2098142f3", "mayProvideTransformerService"),
                target("lightspeed.target.forge.development", "net/minecraftforge/fml/loading/ModDirTransformerDiscoverer",
                        "b9991df65f099d651b4d07729412b4c7f6b2815e6eb1dc6b65f43a5b724b72a9", "mayProvideTransformerService"),
                target("lightspeed.target.neoforge", "net/neoforged/fml/loading/ModDirTransformerDiscoverer",
                        "7a94a5ce380ea983a337d5a8e3ea3eb84e88b584fc172c6ab314948967c9083c", "mayProvideTransformerService"),
                target("lightspeed.target.securejar2", "cpw/mods/jarhandling/impl/Jar",
                        "bba6a4ee9327d364967a3cfec4707d695d5962cb42a20a4b212a26434a5b9055", "mightContain"),
                target("lightspeed.target.securejar3", "cpw/mods/jarhandling/impl/Jar",
                        "ce036690cdf020cafb15d4a3a84a009c6bb50cea8073ea82ada379e5778d0838", "mightContain")
        );
    }

    private static Target target(String property, String className, String sha256, String hookName) {
        return new Target(Path.of(requireProperty(property)), className + ".class", className, sha256, hookName);
    }

    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("missing system property " + name);
        }
        return value;
    }

    private static byte[] readEntry(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(name);
            require(entry != null, "missing target class " + name + " in " + jar);
            return file.getInputStream(entry).readAllBytes();
        }
    }

    private static boolean hasHookCall(byte[] bytes, String hookName) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && HOOK_OWNER.equals(owner) && hookName.equals(name)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static Path jar(Path path, Map<String, byte[]> entries, boolean multiRelease) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().putValue("Multi-Release", "true");
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Target(Path jar, String entry, String className, String sha256, String hookName) {
    }
}
