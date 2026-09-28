package com.ccr4ft3r.lightspeed.bootstrap.compiler;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.scan.ScanMetadataCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class ScanPackCompilerCheck {
    private ScanPackCompilerCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("lightspeed-scan-compiler-");
        Path game = Files.createDirectory(root.resolve("game"));
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path cache = Files.createDirectory(root.resolve("cache"));
        Files.writeString(cache.resolve("scan-image-v2.bin.current"), "corrupt", StandardCharsets.UTF_8);

        Path valid = mods.resolve("A-valid.jar");
        writeJar(valid, false, AnnotatedFixture.class);
        Files.writeString(mods.resolve("b-bad.jar"), "not a jar", StandardCharsets.UTF_8);
        writeJar(mods.resolve("C-multi.jar"), true, SecondFixture.class);
        Path nested = Files.createDirectory(mods.resolve("nested"));
        writeJar(nested.resolve("ignored.jar"), false, SecondFixture.class);

        Invocation first = invoke(game, cache);
        check(first.code() == 0, "corrupt image must fail open: " + first);
        checkSummary(first.output(), 3, 1, 0, 2, 1);
        check(Files.isRegularFile(cache.resolve("scan-image-v2.bin.current")),
                "scan image pointer was not published");

        String physicalKey = ResourceMembershipIndex.physicalJarScanKey(valid);
        check(physicalKey != null, "physical scan key missing");
        SecureJarView firstView = secureJar(valid);
        try {
            ResourceMembershipIndex.register(firstView.root(), valid, alwaysTrue(), new Path[]{valid});
            String unionKey = ResourceMembershipIndex.persistentPathKey(firstView.root());
            check(physicalKey.equals(unionKey), "physical and UnionFS keys differ");
            Object replay = newScanData();
            check(ScanMetadataCache.replay(firstView.root(), replay), "compiled metadata did not replay");
            check(classCount(replay) == 1, "unexpected replayed class count");
            check(hasDeprecatedAnnotation(replay), "replayed annotations are incomplete");
        } finally {
            firstView.close();
        }

        Invocation second = invoke(game, cache);
        check(second.code() == 0, "reuse run failed: " + second);
        checkSummary(second.output(), 3, 0, 1, 2, 0);

        writeJar(valid, false, AnnotatedFixture.class, SecondFixture.class);
        Invocation changed = invoke(game, cache);
        check(changed.code() == 0, "changed-jar run failed: " + changed);
        checkSummary(changed.output(), 3, 1, 0, 2, 2);
        String changedKey = ResourceMembershipIndex.physicalJarScanKey(valid);
        check(!physicalKey.equals(changedKey), "changed jar retained its scan key");

        SecureJarView changedView = secureJar(valid);
        try {
            ResourceMembershipIndex.register(changedView.root(), valid, alwaysTrue(), new Path[]{valid});
            check(changedKey.equals(ResourceMembershipIndex.persistentPathKey(changedView.root())),
                    "changed physical and UnionFS keys differ");
            Object replay = newScanData();
            check(ScanMetadataCache.replay(changedView.root(), replay), "changed metadata did not replay");
            check(classCount(replay) == 2, "changed replayed class count is incomplete");
        } finally {
            changedView.close();
        }

        check(ScanPackCompiler.defaultWorkerCount(1) == 1, "single-processor worker policy changed");
        check(ScanPackCompiler.defaultWorkerCount(7) == 1, "sub-eight worker policy changed");
        check(ScanPackCompiler.defaultWorkerCount(8) == 2, "eight-processor worker policy changed");
        check(ScanPackCompiler.defaultWorkerCount(128) == 2, "worker cap changed");

        check(invokeRaw(new String[0]).code() == ScanPackCompiler.EXIT_USAGE, "usage code changed");
        check(invoke(root.resolve("missing"), cache).code() == ScanPackCompiler.EXIT_PATH,
                "missing game directory must be a path failure");
        Path cacheFile = Files.writeString(root.resolve("cache-file"), "x", StandardCharsets.UTF_8);
        check(invoke(game, cacheFile).code() == ScanPackCompiler.EXIT_PATH,
                "regular-file cache path must be rejected");

        byte[] pointerBeforeFailure = Files.readAllBytes(cache.resolve("scan-image-v2.bin.current"));
        ProcessResult globalFailure = invokeMainWithDisabledCache(game, cache);
        check(globalFailure.code() == ScanPackCompiler.EXIT_GLOBAL_FAILURE,
                "global failure exit code changed: " + globalFailure);
        check(java.util.Arrays.equals(pointerBeforeFailure,
                        Files.readAllBytes(cache.resolve("scan-image-v2.bin.current"))),
                "global failure changed the prior pointer");

        System.out.println("ScanPackCompilerCheck passed pointer="
                + Files.readString(cache.resolve("scan-image-v2.bin.current"), StandardCharsets.UTF_8).trim());
    }

    private static Invocation invoke(Path game, Path cache) {
        return invokeRaw(new String[]{"--game-dir", game.toString(), "--cache-dir", cache.toString()});
    }

    private static Invocation invokeRaw(String[] args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code = PackCompilerMain.run(args, new PrintStream(output), new PrintStream(error));
        return new Invocation(code, output.toString(StandardCharsets.UTF_8).trim(),
                error.toString(StandardCharsets.UTF_8).trim());
    }

    private static void checkSummary(String output, int jars, int compiled, int reused, int skipped, int classes) {
        String prefix = "PACK_COMPILER_OK jars=" + jars + " compiled=" + compiled + " reused=" + reused
                + " skipped=" + skipped + " classes=" + classes + " timeMs=";
        check(output.startsWith(prefix), "unexpected summary: " + output);
        long time = Long.parseLong(output.substring(prefix.length()));
        check(time >= 0, "negative compiler duration");
    }

    private static Object newScanData() throws ReflectiveOperationException {
        return Class.forName("net.minecraftforge.forgespi.language.ModFileScanData")
                .getConstructor().newInstance();
    }

    private static int classCount(Object scanData) throws ReflectiveOperationException {
        return ((Set<?>) scanData.getClass().getMethod("getClasses").invoke(scanData)).size();
    }

    private static boolean hasDeprecatedAnnotation(Object scanData) throws ReflectiveOperationException {
        Set<?> annotations = (Set<?>) scanData.getClass().getMethod("getAnnotations").invoke(scanData);
        for (Object annotation : annotations) {
            Object type = annotation.getClass().getMethod("annotationType").invoke(annotation);
            if ("Ljava/lang/Deprecated;".equals(type.getClass().getMethod("getDescriptor").invoke(type))) {
                return true;
            }
        }
        return false;
    }

    private static SecureJarView secureJar(Path jar) throws ReflectiveOperationException {
        Class<?> secureJar = Class.forName("cpw.mods.jarhandling.SecureJar");
        Method from = secureJar.getMethod("from", Path[].class);
        Object instance = from.invoke(null, (Object) new Path[]{jar});
        Path root = (Path) secureJar.getMethod("getRootPath").invoke(instance);
        return new SecureJarView(root);
    }

    private static BiPredicate<String, String> alwaysTrue() {
        return (name, base) -> true;
    }

    private static ProcessResult invokeMainWithDisabledCache(Path game, Path cache)
            throws IOException, InterruptedException {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dlightspeed.scanMetadataCache=false",
                "-cp", System.getProperty("java.class.path"),
                PackCompilerMain.class.getName(),
                "--game-dir", game.toString(),
                "--cache-dir", cache.toString())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        return new ProcessResult(process.waitFor(), output);
    }

    private static void writeJar(Path target, boolean multiRelease, Class<?>... classes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().putValue("Multi-Release", "true");
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            for (Class<?> type : classes) {
                String name = type.getName().replace('.', '/') + ".class";
                output.putNextEntry(new JarEntry(name));
                try (InputStream input = type.getClassLoader().getResourceAsStream(name)) {
                    if (input == null) {
                        throw new IOException("missing fixture class " + name);
                    }
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Invocation(int code, String output, String error) {
    }

    private record ProcessResult(int code, String output) {
    }

    private record SecureJarView(Path root) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            FileSystem fileSystem = root.getFileSystem();
            if (fileSystem.isOpen() && fileSystem != Path.of(".").getFileSystem()) {
                fileSystem.close();
            }
        }
    }

    @Deprecated
    private static final class AnnotatedFixture {
        @Deprecated
        private int value;

        @Deprecated
        private void execute() {
        }
    }

    private static final class SecondFixture {
    }
}
