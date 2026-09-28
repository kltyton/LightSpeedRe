package com.ccr4ft3r.lightspeed.startup.installation;

import com.ccr4ft3r.lightspeed.cache.persistence.AtomicFileWriter;
import com.google.common.collect.Maps;
import net.minecraftforge.fml.loading.moddiscovery.ModClassVisitor;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

final class PackCompilerCommand {
    static final String MAIN_CLASS = "com.ccr4ft3r.lightspeed.bootstrap.compiler.PackCompilerMain";
    private static final String WINDOWS_SCRIPT = "pack-compiler.cmd";
    private static final String UNIX_SCRIPT = "pack-compiler.sh";

    private PackCompilerCommand() {
    }

    static Prepared prepare(Path gameDirectory, Path agent) throws Exception {
        return prepare(gameDirectory, agent, null, null);
    }

    static Prepared prepare(Path gameDirectory, Path agent, Path owner, String agentDigest) throws Exception {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toAbsolutePath().normalize();
        LinkedHashSet<Path> classpath = new LinkedHashSet<>();
        classpath.add(agent.toAbsolutePath().normalize());
        classpath.add(codeSource(ModClassVisitor.class));
        classpath.add(codeSource(ModFileScanData.class));
        classpath.add(codeSource(ClassReader.class));
        classpath.add(codeSource(Maps.class));
        if (classpath.stream().anyMatch(path -> !Files.isRegularFile(path))) {
            throw new IllegalStateException("Pack Compiler classpath contains a non-file entry");
        }
        Path directory = gameDirectory.resolve(".lightspeed").resolve("bootstrap").toAbsolutePath().normalize();
        Path script = directory.resolve(windows ? WINDOWS_SCRIPT : UNIX_SCRIPT);
        Path cache = gameDirectory.resolve("lightspeed-cache").resolve("bootstrap").toAbsolutePath().normalize();
        int workers = Runtime.getRuntime().availableProcessors() >= 8 ? 2 : 1;
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Xms32m");
        command.add("-Xmx512m");
        command.add("-Dlightspeed.bootstrapCacheDir=" + cache);
        command.add("-Dlightspeed.packCompiler.workers=" + workers);
        if (owner != null && agentDigest != null) {
            command.add("-Dlightspeed.agent.owner=" + owner.toAbsolutePath().normalize());
            command.add("-Dlightspeed.packCompiler.agentDigest=" + agentDigest);
        }
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator,
                classpath.stream().map(Path::toString).toList()));
        command.add(MAIN_CLASS);
        command.add("--game-dir");
        command.add(gameDirectory.toAbsolutePath().normalize().toString());
        command.add("--cache-dir");
        command.add(cache.toString());
        String content = windows ? windowsScript(command) : unixScript(command);
        AtomicFileWriter.write(script, content.getBytes(StandardCharsets.UTF_8));
        if (!windows) {
            try {
                Files.setPosixFilePermissions(script, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
            }
        }
        String invocation = windows
                ? "cmd.exe /d /s /c call " + windowsToken(script.toString())
                : "/bin/sh " + unixToken(script.toString());
        return new Prepared(script, invocation, List.copyOf(classpath));
    }

    static boolean isOwned(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains(WINDOWS_SCRIPT) || normalized.contains(UNIX_SCRIPT)
                || normalized.contains(MAIN_CLASS.toLowerCase(java.util.Locale.ROOT));
    }

    static String windowsScript(List<String> command) {
        List<String> tokens = new ArrayList<>(command.size());
        command.forEach(value -> tokens.add(windowsToken(value)));
        return "@echo off\r\nsetlocal DisableDelayedExpansion\r\n"
                + "start \"\" /b /wait /belownormal " + String.join(" ", tokens)
                + "\r\nexit /b 0\r\n";
    }

    static String unixScript(List<String> command) {
        return "#!/bin/sh\n" + command.stream().map(PackCompilerCommand::unixToken)
                .collect(java.util.stream.Collectors.joining(" ")) + "\nexit 0\n";
    }

    private static String windowsToken(String value) {
        return '"' + value.replace("%", "%%").replace("\"", "\"\"") + '"';
    }

    private static String unixToken(String value) {
        return '\'' + value.replace("'", "'\"'\"'") + '\'';
    }

    private static Path codeSource(Class<?> type) throws URISyntaxException {
        Path direct = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
        return findPhysicalClassSource(type.getName().replace('.', '/') + ".class",
                direct, runtimeClasspath());
    }

    static Path findPhysicalClassSource(String classEntry, Path direct, List<Path> candidates) {
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        List<Path> matches = candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .filter(Files::isRegularFile)
                .filter(path -> containsClass(path, classEntry))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Pack Compiler could not resolve one physical JAR for "
                    + classEntry + " from " + direct + "; matches=" + matches.size());
        }
        return matches.get(0);
    }

    private static List<Path> runtimeClasspath() {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        addClasspath(entries, System.getProperty("legacyClassPath"));
        addClasspath(entries, System.getProperty("java.class.path"));
        addClasspath(entries, System.getProperty("jdk.module.path"));
        return List.copyOf(entries);
    }

    private static void addClasspath(Set<Path> entries, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!token.isBlank()) {
                try {
                    entries.add(Path.of(token).toAbsolutePath().normalize());
                } catch (RuntimeException ignored) {
                    // An invalid launcher token cannot be a physical helper dependency.
                }
            }
        }
    }

    private static boolean containsClass(Path candidate, String classEntry) {
        try (JarFile jar = new JarFile(candidate.toFile(), false)) {
            return jar.getJarEntry(classEntry) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    record Prepared(Path script, String invocation, List<Path> classpath) {
    }
}
