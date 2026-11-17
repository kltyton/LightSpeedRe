package com.ccr4ft3r.lightspeed.startup.installation;

import com.ccr4ft3r.lightspeed.cache.persistence.AtomicFileWriter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Supports additional third-party launcher layouts while keeping updates reversible and atomic. */
final class ExtendedLauncherProfiles {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final List<String> PRISM_GLOBAL_CONFIGS = List.of(
            "prismlauncher.cfg",
            "multimc.cfg",
            "polymc.cfg"
    );

    private ExtendedLauncherProfiles() {
    }

    /**
     * Enables per-instance JVM argument overrides in Prism/MultiMC/PolyMC while carrying over
     * any existing global {@code JvmArgs} so user GC/memory settings remain intact.
     */
    static String enablePrismOverrideWithGlobalArgs(Path instanceConfig, String content) throws Exception {
        validatePrismInstanceSettings(instanceConfig, content);
        String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(content.split("\\R", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        int overrideIndex = -1;
        int jvmArgsIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("OverrideJavaArgs=")) {
                if (overrideIndex >= 0) {
                    throw new IOException("Multiple OverrideJavaArgs entries in " + instanceConfig);
                }
                overrideIndex = i;
            } else if (line.startsWith("JvmArgs=")) {
                if (jvmArgsIndex >= 0) {
                    throw new IOException("Multiple JvmArgs entries in " + instanceConfig);
                }
                jvmArgsIndex = i;
            }
        }

        // An inactive instance value is not the value used by the current launch.
        // Resolve the owning global configuration before changing either setting.
        String inherited = findGlobalPrismJvmArgs(instanceConfig);
        String encoded = encodePrismValue(instanceConfig, inherited);
        if (overrideIndex >= 0) {
            lines.set(overrideIndex, "OverrideJavaArgs=true");
        }
        if (jvmArgsIndex >= 0) {
            lines.set(jvmArgsIndex, "JvmArgs=" + encoded);
        }
        if (overrideIndex < 0) {
            insertPrismGeneralSetting(lines, "OverrideJavaArgs=true");
        }
        if (jvmArgsIndex < 0) {
            insertPrismGeneralSetting(lines, "JvmArgs=" + encoded);
        }
        return String.join(lineSeparator, lines) + lineSeparator;
    }

    static Boolean tryUpdateAtLauncher(Path gameDirectory, List<String> desiredArguments) throws Exception {
        Path instanceFile = gameDirectory.resolve("instance.json");
        if (!Files.isRegularFile(instanceFile)) {
            return null;
        }
        String content = Files.readString(instanceFile, StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(content);
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("launcher") || !root.get("launcher").isJsonObject()) {
            return null;
        }
        JsonObject launcher = root.getAsJsonObject("launcher");
        if (!root.has("id") && !launcher.has("javaArguments") && !launcher.has("loaderVersion")) {
            return null;
        }
        String existing = launcher.has("javaArguments") && launcher.get("javaArguments").isJsonPrimitive()
                ? launcher.get("javaArguments").getAsString()
                : "";
        String updated = LaunchProfileInstaller.mergeArgumentLine(existing, desiredArguments);
        if (updated.equals(existing)) {
            return Boolean.FALSE;
        }
        launcher.addProperty("javaArguments", updated);
        AtomicFileWriter.write(instanceFile, (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        return Boolean.TRUE;
    }

    static boolean isCurseForgeInstance(Path gameDirectory, Path officialProfilesFile) {
        if (officialProfilesFile != null) {
            Path parent = officialProfilesFile.getParent();
            if (parent != null && "Install".equalsIgnoreCase(parent.getFileName().toString())) {
                return true;
            }
        }
        Path current = gameDirectory.toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (Files.isRegularFile(current.resolve("minecraftinstance.json"))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    static boolean isBakaXlPresent(Path gameDirectory, String versionId) {
        Path normalized = gameDirectory.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized.resolve("BakaXL_VerInfo.json"))) {
            return true;
        }
        if (versionId != null && !versionId.isBlank()
                && Files.isRegularFile(normalized.resolve("versions").resolve(versionId).resolve("BakaXL_VerInfo.json"))) {
            return true;
        }
        return Files.isDirectory(normalized.resolve("BakaXL"));
    }

    private static String findGlobalPrismJvmArgs(Path instanceConfig) throws IOException {
        Set<Path> candidates = new LinkedHashSet<>();
        Path current = instanceConfig.toAbsolutePath().normalize().getParent();
        for (int depth = 0; current != null && depth < 5; depth++) {
            for (String name : PRISM_GLOBAL_CONFIGS) {
                candidates.add(current.resolve(name));
            }
            current = current.getParent();
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            candidates.add(Path.of(appData, "PrismLauncher", "prismlauncher.cfg"));
            candidates.add(Path.of(appData, "MultiMC", "multimc.cfg"));
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            candidates.add(Path.of(userHome, ".local", "share", "PrismLauncher", "prismlauncher.cfg"));
        }
        Path instanceDirectory = instanceConfig.toAbsolutePath().normalize().getParent();
        Path instancesDirectory = instanceDirectory == null ? null : instanceDirectory.getParent();
        if (instancesDirectory == null) {
            throw new IOException("Cannot identify the instance directory for " + instanceConfig);
        }
        Path matchedConfig = null;
        String matchedArgs = null;
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            String configuredInstances = readIniProperty(candidate, "InstanceDir");
            if (configuredInstances == null) configuredInstances = "instances";
            else configuredInstances = decodePrismValue(candidate, configuredInstances);
            if (configuredInstances.isBlank()) continue;
            Path configuredDirectory;
            try {
                configuredDirectory = candidate.getParent().resolve(configuredInstances).normalize();
            } catch (RuntimeException exception) {
                continue;
            }
            if (!Files.isDirectory(configuredDirectory)
                    || !Files.isSameFile(instancesDirectory, configuredDirectory)) {
                continue;
            }
            if (matchedConfig != null && !Files.isSameFile(matchedConfig, candidate)) {
                throw new IOException("Multiple launcher configurations claim instance directory " + instancesDirectory);
            }
            String args = readIniProperty(candidate, "JvmArgs");
            matchedConfig = candidate;
            matchedArgs = args == null ? "" : decodePrismValue(candidate, args);
        }
        if (matchedConfig == null) {
            throw new IOException("Cannot identify the global Prism/MultiMC configuration for " + instanceConfig);
        }
        return matchedArgs;
    }

    /** Decodes the string subset shared by Prism's legacy and Qt INI formats. */
    static String decodePrismValue(Path file, String encoded) throws IOException {
        boolean legacy = legacyPrismFormat(file);
        StringBuilder value = new StringBuilder(encoded.length());
        boolean quoted = false;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '\\') {
                if (++i >= encoded.length()) throw new IOException("Incomplete INI escape in " + file);
                char escaped = encoded.charAt(i);
                if (escaped != '\\' && escaped != '"' && !(legacy && escaped == '#')) {
                    throw new IOException("Unsupported INI escape in " + file);
                }
                value.append(escaped);
            } else if (!legacy && c == '"') {
                quoted = !quoted;
            } else if (c == '#' || !legacy && !quoted && (c == ',' || c == ';')) {
                throw new IOException("Unsupported INI value syntax in " + file);
            } else {
                value.append(c);
            }
        }
        if (quoted) throw new IOException("Unterminated INI string in " + file);
        String decoded = value.toString();
        if (legacy && decoded.length() >= 2 && decoded.startsWith("\"") && decoded.endsWith("\"")
                && (decoded.indexOf('=') >= 0 || decoded.indexOf(',') >= 0 || decoded.indexOf(';') >= 0)) {
            decoded = decoded.substring(1, decoded.length() - 1);
        }
        if (decoded.startsWith("@") || decoded.indexOf('\n') >= 0 || decoded.indexOf('\r') >= 0) {
            throw new IOException("Unsupported INI value in " + file);
        }
        return decoded;
    }

    /** Writes a value that each supported Prism INI reader decodes back to the same string. */
    static String encodePrismValue(Path file, String value) throws IOException {
        boolean legacy = legacyPrismFormat(file);
        if (value.startsWith("@") || value.indexOf('#') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("Unsupported INI value in " + file);
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        if (!legacy || value.indexOf('=') >= 0 || value.indexOf(',') >= 0 || value.indexOf(';') >= 0) {
            return '"' + escaped + '"';
        }
        return escaped;
    }

    private static boolean legacyPrismFormat(Path file) throws IOException {
        String version = readGeneralIniProperty(file, "ConfigVersion");
        if (version == null) return true;
        if (version.equals("1.2") || version.equals("1.3")) return false;
        throw new IOException("Unsupported Prism INI ConfigVersion " + version + " in " + file);
    }

    static void validatePrismInstanceSettings(Path file, String content) throws IOException {
        boolean general = true;
        boolean seenOverride = false;
        boolean seenJvmArgs = false;
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (!raw.equals(line)) {
                    throw new IOException("Unsupported INI section syntax in " + file);
                }
                general = line.equals("[General]");
                continue;
            }
            if (line.startsWith("#") || line.startsWith(";")) continue;
            int separator = line.indexOf('=');
            if (separator < 0) continue;
            String key = line.substring(0, separator).trim();
            if ((key.equals("OverrideJavaArgs") || key.equals("JvmArgs"))
                    && (!general || !raw.startsWith(key + "="))) {
                throw new IOException("Unsupported " + key + " entry in " + file);
            }
            if (key.equals("OverrideJavaArgs")) {
                if (seenOverride) throw new IOException("Multiple OverrideJavaArgs entries in " + file);
                seenOverride = true;
            } else if (key.equals("JvmArgs")) {
                if (seenJvmArgs) throw new IOException("Multiple JvmArgs entries in " + file);
                seenJvmArgs = true;
            }
        }
    }

    static void insertPrismGeneralSetting(List<String> lines, String setting) {
        int general = lines.indexOf("[General]");
        if (general >= 0) {
            int index = general + 1;
            while (index < lines.size() && !(lines.get(index).startsWith("[")
                    && lines.get(index).endsWith("]"))) index++;
            lines.add(index, setting);
        } else if (lines.stream().anyMatch(line -> line.startsWith("[") && line.endsWith("]"))) {
            lines.add(0, setting);
            lines.add(0, "[General]");
        } else {
            lines.add(setting);
        }
    }

    private static String readIniProperty(Path file, String key) throws IOException {
        return readIniProperty(file, key, readGeneralIniProperty(file, "ConfigVersion") == null);
    }

    private static String readGeneralIniProperty(Path file, String key) throws IOException {
        return readIniProperty(file, key, false);
    }

    private static String readIniProperty(Path file, String key, boolean legacy) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        boolean general = true;
        String value = null;
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                general = line.equals("[General]");
            } else if ((legacy || general) && !line.startsWith("#") && !line.startsWith(";")) {
                int separator = line.indexOf('=');
                if (separator >= 0 && line.substring(0, separator).trim().equals(key)) {
                    if (value != null) throw new IOException("Multiple " + key + " entries in " + file);
                    value = line.substring(separator + 1).trim();
                }
            }
        }
        return value;
    }
}
