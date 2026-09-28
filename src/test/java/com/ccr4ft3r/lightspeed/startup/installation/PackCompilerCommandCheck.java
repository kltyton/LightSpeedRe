package com.ccr4ft3r.lightspeed.startup.installation;

import java.util.List;

public final class PackCompilerCommandCheck {
    private PackCompilerCommandCheck() {
    }

    public static void main(String[] args) throws Exception {
        List<String> command = List.of(
                "C:\\Java Home\\bin\\java.exe",
                "-Dpath=C:\\Pack %TEMP% & Data",
                "-cp", "C:\\One A.jar;C:\\Two&B.jar",
                PackCompilerCommand.MAIN_CLASS);
        String windows = PackCompilerCommand.windowsScript(command);
        require(windows.startsWith("@echo off\r\nsetlocal DisableDelayedExpansion\r\n"),
                "Windows compiler script does not disable expansion");
        require(windows.contains("%%TEMP%%") && windows.contains("\"C:\\One A.jar;C:\\Two&B.jar\""),
                "Windows compiler script did not preserve special path tokens");
        require(windows.contains("start \"\" /b /wait /belownormal"),
                "Windows compiler process is not launched below normal priority");
        require(windows.endsWith("exit /b 0\r\n"), "Windows compiler failure would block game launch");

        String unix = PackCompilerCommand.unixScript(List.of("/java home/java", "a'b", "x&y"));
        require(unix.contains("'/java home/java'") && unix.contains("'a'\"'\"'b'")
                        && unix.endsWith("exit 0\n"),
                "Unix compiler script quoting or fail-open changed");
        require(PackCompilerCommand.isOwned("cmd /c C:/game/pack-compiler.cmd")
                        && !PackCompilerCommand.isOwned("echo user-command"),
                "managed pre-launch command ownership is ambiguous");
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("lightspeed-compiler-command-");
        try {
            java.nio.file.Path game = directory.resolve("Game With Spaces");
            java.nio.file.Path agent = directory.resolve("agent.jar");
            java.nio.file.Files.createDirectories(game);
            java.nio.file.Files.write(agent, new byte[]{1});
            checkPhysicalClasspathFallback(directory);
            PackCompilerCommand.Prepared prepared = PackCompilerCommand.prepare(game, agent);
            String generated = java.nio.file.Files.readString(prepared.script());
            require(generated.contains(PackCompilerCommand.MAIN_CLASS)
                            && prepared.classpath().size() >= 5
                            && prepared.classpath().stream().allMatch(java.nio.file.Files::isRegularFile),
                    "Pack Compiler runtime classpath was not resolved to physical JARs");
            require(PackCompilerCommand.isOwned(prepared.invocation()),
                    "generated pre-launch invocation is not recognized as managed");
            String builtAgent = System.getProperty("lightspeed.agent.jar");
            if (builtAgent != null) {
                java.nio.file.Path realAgent = java.nio.file.Path.of(builtAgent);
                byte[] agentBytes = java.nio.file.Files.readAllBytes(realAgent);
                String digest = java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(agentBytes));
                java.nio.file.Path owner = directory.resolve("owner.jar");
                try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(
                        java.nio.file.Files.newOutputStream(owner))) {
                    output.putNextEntry(new java.util.jar.JarEntry("META-INF/mods.toml"));
                    output.write(1);
                    output.closeEntry();
                    output.putNextEntry(new java.util.jar.JarEntry("META-INF/lightspeed/bootstrap-agent.jar"));
                    output.write(agentBytes);
                    output.closeEntry();
                }
                java.nio.file.Path mods = java.nio.file.Files.createDirectories(game.resolve("mods"));
                String className = PackCompilerCommandCheck.class.getName().replace('.', '/') + ".class";
                try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(
                        java.nio.file.Files.newOutputStream(mods.resolve("fixture.jar")))) {
                    output.putNextEntry(new java.util.jar.JarEntry(className));
                    try (java.io.InputStream input = PackCompilerCommandCheck.class.getClassLoader()
                            .getResourceAsStream(className)) {
                        require(input != null, "compiler fixture class is missing");
                        input.transferTo(output);
                    }
                    output.closeEntry();
                }
                PackCompilerCommand.Prepared executable = PackCompilerCommand.prepare(
                        game, realAgent, owner, digest);
                String guardedScript = java.nio.file.Files.readString(executable.script());
                require(guardedScript.contains("lightspeed.agent.owner")
                                && guardedScript.contains("lightspeed.packCompiler.agentDigest=" + digest),
                        "generated compiler script omitted owner guard");
                boolean windowsHost = System.getProperty("os.name", "").startsWith("Windows");
                Process process = new ProcessBuilder(windowsHost
                        ? List.of("cmd.exe", "/d", "/s", "/c", "call", executable.script().toString())
                        : List.of("/bin/sh", executable.script().toString()))
                        .directory(game.toFile()).redirectErrorStream(true).start();
                String processOutput;
                try (java.io.InputStream input = process.getInputStream()) {
                    processOutput = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                require(process.waitFor() == 0 && processOutput.contains("PACK_COMPILER_OK"),
                        "generated pre-launch script did not execute the packaged compiler: " + processOutput);
                require(java.nio.file.Files.isRegularFile(game.resolve(
                                "lightspeed-cache/bootstrap/scan-image-v2.bin.current")),
                        "generated pre-launch script did not publish scan PackImage");
            }
        } finally {
            try (var paths = java.nio.file.Files.walk(directory)) {
                for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("PACK_COMPILER_COMMAND_OK");
    }

    private static void checkPhysicalClasspathFallback(java.nio.file.Path directory) throws Exception {
        String entry = "fixture/PhysicalSource.class";
        java.nio.file.Path unrelated = directory.resolve("unrelated.jar");
        java.nio.file.Path expected = directory.resolve("physical-source.jar");
        writeJarEntry(unrelated, "fixture/Other.class");
        writeJarEntry(expected, entry);
        java.nio.file.Path resolved = PackCompilerCommand.findPhysicalClassSource(
                entry, directory, List.of(unrelated, expected));
        require(resolved.equals(expected.toAbsolutePath().normalize()),
                "non-file class source did not fall back to the physical classpath JAR");
    }

    private static void writeJarEntry(java.nio.file.Path jar, String entry) throws Exception {
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(
                java.nio.file.Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry(entry));
            output.write(1);
            output.closeEntry();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
