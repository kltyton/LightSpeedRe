package io.github.kltyton.temple.workspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class BuildScripts {
    public enum Dsl {
        GROOVY("groovy", "Groovy (.gradle)"), KOTLIN("kotlin", "Kotlin DSL (.gradle.kts)");
        public final String id;
        private final String label;
        Dsl(String id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
        public static Dsl parse(String value) {
            return Arrays.stream(values()).filter(dsl -> dsl.id.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown Gradle script language: " + value));
        }
    }

    private BuildScripts() {}

    public static void createProject(Path root) throws IOException {
        if (dsl(root) != Dsl.KOTLIN) return;
        for (String file : List.of("build.gradle.kts", "settings.gradle.kts", "buildSrc/build.gradle.kts",
                "gradle/target-conventions/base.gradle.kts", "gradle/target-conventions/publish.gradle.kts")) {
            install(root.resolve(file), file);
            Files.deleteIfExists(root.resolve(file.substring(0, file.length() - 4)));
        }
        for (String file : List.of("fabric", "forge", "forgegradle", "neoforge"))
            Files.deleteIfExists(root.resolve("gradle/target-conventions/" + file + ".gradle"));
        for (String parent : List.of("targets", "gradle/target-blueprints")) {
            Path directory = root.resolve(parent);
            if (!Files.isDirectory(directory)) continue;
            try (var paths = Files.list(directory)) {
                for (Path target : paths.filter(Files::isDirectory).toList()) prepareTarget(root, target);
            }
        }
    }

    public static void prepareTarget(Path root, Path target) throws IOException {
        if (dsl(root) != Dsl.KOTLIN) return;
        Properties values = read(target.resolve("gradle.properties"));
        String variant = switch (values.getProperty("loader")) {
            case "fabric" -> Integer.parseInt(values.getProperty("java_version")) >= 25 ? "fabric-unmapped" : "fabric-mapped";
            case "forge" -> values.containsKey("forgegradle_version") ? "forgegradle" : "forge";
            case "neoforge" -> "neoforge";
            default -> throw new IllegalArgumentException("Unknown Loader: " + values.getProperty("loader"));
        };
        install(target.resolve("build.gradle.kts"), "targets/" + variant + ".gradle.kts");
        install(target.resolve("settings.gradle.kts"), "targets/settings.gradle.kts");
        Files.deleteIfExists(target.resolve("build.gradle"));
        Files.deleteIfExists(target.resolve("settings.gradle"));
    }

    public static Dsl dsl(Path root) throws IOException {
        return Dsl.parse(read(root.resolve("gradle.properties")).getProperty("temple_build_dsl", "groovy"));
    }

    private static Properties read(Path file) throws IOException {
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
        return values;
    }

    private static void install(Path destination, String resource) throws IOException {
        String name = "/io/github/kltyton/temple/dsl/kotlin/" + resource;
        try (InputStream stream = BuildScripts.class.getResourceAsStream(name)) {
            if (stream == null) throw new FileNotFoundException("Missing Kotlin DSL template: " + name);
            Files.createDirectories(destination.getParent());
            Files.write(destination, stream.readAllBytes());
        }
    }
}
