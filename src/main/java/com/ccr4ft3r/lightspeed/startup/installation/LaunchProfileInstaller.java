package com.ccr4ft3r.lightspeed.startup.installation;

import com.ccr4ft3r.lightspeed.cache.persistence.AtomicFileWriter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LaunchProfileInstaller {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String PCL_JVM_ARGUMENTS = "VersionAdvanceJvm:";

    private LaunchProfileInstaller() {
    }

    static Result install(Path gameDirectory, Path owner, Path agent) throws Exception {
        Path pclSetup = gameDirectory.resolve("PCL").resolve("Setup.ini");
        if (Files.isRegularFile(pclSetup)) {
            return updatePcl(pclSetup, gameDirectory, owner, agent) ? Result.PCL : Result.UNCHANGED;
        }

        Path prismConfig = locatePrismConfig(gameDirectory);
        if (prismConfig != null && prismUsesInstanceArguments(prismConfig)) {
            return updatePrism(prismConfig, gameDirectory, owner, agent) ? Result.PRISM : Result.UNCHANGED;
        }

        Path versionJson = locateVersionJson(gameDirectory);
        if (versionJson != null) {
            return updateVersionJson(versionJson, gameDirectory, owner, agent) ? Result.VERSION_JSON : Result.UNCHANGED;
        }
        return Result.UNSUPPORTED;
    }

    static boolean updatePcl(Path setup, Path gameDirectory, Path owner, Path agent) throws Exception {
        String content = Files.readString(setup, StandardCharsets.UTF_8);
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(content.lines().toList());
        String ownerArgument = quote(BootstrapAgentInstaller.ownerArgument(owner));
        String agentArgument = quote(BootstrapAgentInstaller.agentArgument(agent));
        String cacheArgument = quote(BootstrapAgentInstaller.cacheDirectoryArgument(gameDirectory));
        boolean found = false;
        boolean changed = false;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith(PCL_JVM_ARGUMENTS)) {
                continue;
            }
            found = true;
            String existing = line.substring(PCL_JVM_ARGUMENTS.length());
            String updated = removeLightspeedArguments(existing);
            updated = append(updated, cacheArgument, ownerArgument, agentArgument);
            String replacement = PCL_JVM_ARGUMENTS + updated;
            changed = !replacement.equals(line);
            lines.set(index, replacement);
            break;
        }

        if (!found) {
            lines.add(PCL_JVM_ARGUMENTS + append("", cacheArgument, ownerArgument, agentArgument));
            changed = true;
        }
        if (!changed) {
            return false;
        }

        backupOnce(setup);
        String updated = String.join(newline, lines) + (content.endsWith("\n") ? newline : "");
        AtomicFileWriter.write(setup, updated.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    static boolean updateVersionJson(Path file, Path gameDirectory, Path owner, Path agent) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject arguments = root.has("arguments") && root.get("arguments").isJsonObject()
                ? root.getAsJsonObject("arguments")
                : new JsonObject();
        JsonArray current = arguments.has("jvm") && arguments.get("jvm").isJsonArray()
                ? arguments.getAsJsonArray("jvm")
                : new JsonArray();
        JsonArray updated = new JsonArray();
        for (JsonElement element : current) {
            if (!isLightspeedArgument(element)) {
                updated.add(element.deepCopy());
            }
        }
        String ownerArgument = BootstrapAgentInstaller.ownerArgument(owner);
        String agentArgument = BootstrapAgentInstaller.agentArgument(agent);
        String cacheArgument = BootstrapAgentInstaller.cacheDirectoryArgument(gameDirectory);
        updated.add(cacheArgument);
        updated.add(ownerArgument);
        updated.add(agentArgument);

        if (current.equals(updated)) {
            return false;
        }
        arguments.add("jvm", updated);
        root.add("arguments", arguments);
        backupOnce(file);
        AtomicFileWriter.write(file, (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        return true;
    }

    static boolean updatePrism(Path config, Path gameDirectory, Path owner, Path agent) throws Exception {
        String content = Files.readString(config, StandardCharsets.UTF_8);
        if (!content.lines().anyMatch(line -> line.equals("OverrideJavaArgs=true"))) {
            return false;
        }
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(content.lines().toList());
        String ownerArgument = quote(BootstrapAgentInstaller.ownerArgument(owner));
        String agentArgument = quote(BootstrapAgentInstaller.agentArgument(agent));
        String cacheArgument = quote(BootstrapAgentInstaller.cacheDirectoryArgument(gameDirectory));
        boolean foundArguments = false;
        boolean changed = false;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("JvmArgs=")) {
                foundArguments = true;
                String updated = append(removeLightspeedArguments(line.substring("JvmArgs=".length())),
                        cacheArgument, ownerArgument, agentArgument);
                String replacement = "JvmArgs=" + updated;
                changed |= !replacement.equals(line);
                lines.set(index, replacement);
            }
        }
        if (!foundArguments) {
            lines.add("JvmArgs=" + append("", cacheArgument, ownerArgument, agentArgument));
            changed = true;
        }
        if (!changed) {
            return false;
        }

        backupOnce(config);
        String updated = String.join(newline, lines) + (content.endsWith("\n") ? newline : "");
        AtomicFileWriter.write(config, updated.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private static Path locateVersionJson(Path gameDirectory) {
        String version = launchVersion();
        Set<Path> candidates = new LinkedHashSet<>();
        if (version != null) {
            candidates.add(gameDirectory.resolve(version + ".json"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path locatePrismConfig(Path gameDirectory) {
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(gameDirectory.resolve("instance.cfg"));
        Path parent = gameDirectory.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("instance.cfg"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean prismUsesInstanceArguments(Path config) {
        try {
            return Files.readAllLines(config, StandardCharsets.UTF_8).stream()
                    .anyMatch(line -> line.equals("OverrideJavaArgs=true"));
        } catch (Exception exception) {
            return false;
        }
    }

    private static String launchVersion() {
        String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        for (int index = 0; index + 1 < arguments.length; index++) {
            if (arguments[index].equals("--version")) {
                return arguments[index + 1];
            }
        }
        return null;
    }

    private static String removeLightspeedArguments(String arguments) {
        List<String> retained = new ArrayList<>();
        for (String token : tokenize(arguments)) {
            String normalized = unquote(token).toLowerCase(Locale.ROOT);
            if (normalized.contains("lightspeed-bootstrap-agent")
                    || normalized.startsWith("-dlightspeed.agent.owner=")
                    || normalized.startsWith("-dlightspeed.bootstrapcachedir=")) {
                continue;
            }
            retained.add(token);
        }
        return String.join(" ", retained);
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static List<String> tokenize(String arguments) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < arguments.length(); index++) {
            char character = arguments.charAt(index);
            if (character == '"') {
                quoted = !quoted;
                token.append(character);
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!token.isEmpty()) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(character);
            }
        }
        if (!token.isEmpty()) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    private static String append(String existing, String... arguments) {
        String suffix = String.join(" ", arguments);
        return existing.isBlank() ? suffix : existing + ' ' + suffix;
    }

    private static String quote(String argument) {
        return '"' + argument.replace("\"", "\\\"") + '"';
    }

    private static boolean isLightspeedArgument(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return false;
        }
        String value = element.getAsString().toLowerCase(Locale.ROOT);
        return value.contains("lightspeed-bootstrap-agent")
                || value.startsWith("-dlightspeed.agent.owner=")
                || value.startsWith("-dlightspeed.bootstrapcachedir=");
    }

    private static void backupOnce(Path file) throws Exception {
        Path backup = file.resolveSibling(file.getFileName() + ".lightspeed-backup");
        if (!Files.exists(backup)) {
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    enum Result {
        PCL("PCL per-instance JVM arguments", true),
        PRISM("Prism Launcher or MultiMC instance JVM arguments", true),
        VERSION_JSON("Minecraft version JSON", true),
        UNCHANGED("existing launcher configuration", false),
        UNSUPPORTED("unsupported launcher configuration", false);

        private final String description;
        private final boolean changed;

        Result(String description, boolean changed) {
            this.description = description;
            this.changed = changed;
        }

        String description() {
            return description;
        }

        boolean changed() {
            return changed;
        }
    }
}
