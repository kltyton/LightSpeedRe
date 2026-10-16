package io.github.kltyton.temple.workspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class TargetPlan {
    public static final List<String> BLUEPRINTS = List.of("fabric-1.20.1", "fabric-1.21.1", "fabric-26.1.2",
            "forge-1.18.2", "forge-1.19.2", "forge-1.20.1", "neoforge-1.21.1", "neoforge-26.1.2", "neoforge-26.2.0");

    public static String blueprint(String loader, String minecraft, int javaVersion) {
        String exact = loader + "-" + minecraft;
        if (BLUEPRINTS.contains(exact)) return exact;
        return switch (loader) {
            case "fabric" -> javaVersion >= 25 ? "fabric-26.1.2" : "fabric-1.20.1";
            case "forge" -> "forge-1.20.1";
            case "neoforge" -> javaVersion >= 25 ? "neoforge-26.1.2" : "neoforge-1.21.1";
            default -> throw new IllegalArgumentException("Unknown loader: " + loader);
        };
    }
    private TargetPlan() {
    }

    private record PlannedTarget(Map<String, String> row, Path from, Path to, Properties properties, Properties wrapper) {}

    public static void apply(Path root, List<Map<String, String>> selections, boolean removeBlueprints) throws IOException {
        if (selections.isEmpty()) throw new IllegalArgumentException("Select at least one target");
        BuildScripts.dsl(root);
        Path targets = root.resolve("targets").toRealPath();
        Set<String> wanted = new LinkedHashSet<>(), blueprints = new LinkedHashSet<>();
        List<PlannedTarget> plans = new ArrayList<>();
        for (Map<String, String> row : selections) {
            String loader = row.get("loader"), mc = row.get("minecraft_version"), source = row.get("blueprint");
            if (!Set.of("fabric", "forge", "neoforge").contains(loader) || mc == null || !mc.matches("[0-9]+(\\.[0-9]+)*")
                    || source == null || !source.matches("(fabric|forge|neoforge)-[0-9.]+"))
                throw new IllegalArgumentException("Invalid target selection");
            String id = loader + "-" + mc;
            if (!wanted.add(id)) throw new IllegalArgumentException("Duplicate target: " + id);
            for (String key : List.of("loader_version", "java_version", "gradle_java_version"))
                if (row.get(key) == null || row.get(key).isBlank()) throw new IllegalArgumentException("Missing " + key + ": " + id);
            if (!row.get("loader_version").matches("[a-zA-Z0-9.+_-]+")
                    || !row.get("java_version").matches("[0-9]+") || !row.get("gradle_java_version").matches("[0-9]+"))
                throw new IllegalArgumentException("Invalid version selection: " + id);
            if (loader.equals("fabric") && (row.get("fabric_api_version") == null
                    || !row.get("fabric_api_version").matches("[a-zA-Z0-9.+_-]+")))
                throw new IllegalArgumentException("Missing matching Fabric API version: " + id);
            if (row.containsKey("wrapper_version") && !row.get("wrapper_version").matches("[0-9]+(\\.[0-9]+)+"))
                throw new IllegalArgumentException("Invalid Gradle version: " + id);
            blueprints.add(source);
            Path from = targets.resolve(source), to = targets.resolve(id);
            if (!Files.isDirectory(from)) from = root.resolve("gradle/target-blueprints").resolve(source);
            if (!removeBlueprints && Files.exists(to))
                throw new IllegalArgumentException("Target already exists: " + id);
            if (!from.equals(to) && Files.exists(to)) throw new IllegalArgumentException("Target already exists: " + id);
            Properties properties = read(from.resolve("gradle.properties"));
            Properties wrapper = read(from.resolve("gradle/wrapper/gradle-wrapper.properties"));
            if (!loader.equals(properties.getProperty("loader")) || properties.getProperty("shared_sources") == null)
                throw new IllegalArgumentException("Invalid blueprint: " + source);
            try (Stream<Path> paths = Files.walk(from)) {
                if (paths.anyMatch(Files::isSymbolicLink)) throw new IllegalArgumentException("Blueprint contains symbolic links: " + source);
            }
            plans.add(new PlannedTarget(new LinkedHashMap<>(row), from, to, properties, wrapper));
        }
        List<Path> retired = new ArrayList<>();
        if (removeBlueprints) for (String source : blueprints)
            if (!wanted.contains(source)) {
                Path directory = targets.resolve(source);
                if (!Files.exists(directory)) continue;
                try (Stream<Path> paths = Files.walk(directory)) {
                    List<Path> contents = paths.toList();
                    for (Path path : contents)
                        if (Files.isRegularFile(path) && !knownBlueprintFile(directory.relativize(path).toString().replace('\\', '/')))
                            throw new IllegalArgumentException("Blueprint has user files; retained: " + path);
                    retired.addAll(contents);
                }
            }
        // Copy every variant before modifying a blueprint also selected as a target.
        for (PlannedTarget plan : plans) {
            Path from = plan.from(), to = plan.to();
            if (!from.equals(to)) {
                try (Stream<Path> paths = Files.walk(from)) {
                    for (Path path : paths.toList()) {
                        Path relative = from.relativize(path);
                        if (relative.toString().matches("(^|.*[\\\\/])(build|\\.gradle|\\.idea)([\\\\/].*|$)"))
                            continue;
                        Path destination = to.resolve(relative);
                        if (Files.isDirectory(path)) Files.createDirectories(destination);
                        else Files.copy(path, destination);
                    }
                }
            }
        }
        for (PlannedTarget plan : plans) {
            Map<String, String> row = plan.row();
            String loader = row.get("loader"), mc = row.get("minecraft_version");
            Path to = plan.to();
            Properties properties = plan.properties(), wrapper = plan.wrapper();
            String oldMc = properties.getProperty("minecraft_version");
            row.forEach((key, value) -> {
                if (!key.equals("blueprint")) properties.setProperty(key, value);
            });
            properties.setProperty("minecraft_version_range", "[" + mc + "]");
            properties.setProperty("loader_version_range", loader.equals("fabric") ? ">=" + row.get("loader_version") :
                    "[" + row.get("loader_version") + ",)");
            properties.setProperty("shared_sources", properties.getProperty("shared_sources").replace(oldMc, mc));
            if (loader.equals("forge"))
                properties.setProperty("fml_version_range", "[" + row.get("loader_version").split("\\.")[0] + ",)");
            if (loader.equals("neoforge") && mc.equals("1.20.6")) properties.setProperty("fml_version_range", "[3,)");
            write(to.resolve("gradle.properties"), properties);
            String wrapperVersion = row.get("wrapper_version");
            String wrapperUrl = "https://services.gradle.org/distributions/gradle-" + wrapperVersion + "-bin.zip";
            if (wrapperVersion != null && !wrapperUrl.equals(wrapper.getProperty("distributionUrl"))) {
                wrapper.setProperty("distributionUrl", wrapperUrl);
                wrapper.remove("distributionSha256Sum");
                write(to.resolve("gradle/wrapper/gradle-wrapper.properties"), wrapper);
            }
            if (row.containsKey("forgegradle_version") && BuildScripts.dsl(root) == BuildScripts.Dsl.GROOVY) {
                Files.writeString(to.resolve("build.gradle"), "plugins {\n    id 'net.minecraftforge.gradle' version \"${forgegradle_version}\"\n" +
                        "    id 'me.modmuss50.mod-publish-plugin' version \"${publish_plugin_version}\"\n}\n" +
                        "apply from: file('../../gradle/target-conventions/forgegradle.gradle')\n");
            }
            BuildScripts.prepareTarget(root, to);
        }
        for (Path path : retired.stream().sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        Properties identity = read(root.resolve("gradle.properties"));
        if (removeBlueprints) {
            identity.setProperty("temple_default_target", wanted.iterator().next());
            Files.createDirectories(root.resolve(".temple"));
            Files.writeString(root.resolve(".temple/active-target"), wanted.iterator().next());
        }
        identity.setProperty("temple_idea_project", "true");
        write(root.resolve("gradle.properties"), identity);
    }

    private static boolean knownBlueprintFile(String file) {
        return Set.of("build.gradle", "settings.gradle", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
                "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                "gradle/wrapper/kltyton-wrapper.base64", "src/main/resources/fabric.mod.json",
                "src/main/resources/META-INF/mods.toml", "src/main/resources/META-INF/neoforge.mods.toml").contains(file);
    }

    private static Properties read(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void write(Path path, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
    }
}
