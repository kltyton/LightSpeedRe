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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

final class LaunchProfileInstaller {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String PCL_JVM_ARGUMENTS = "VersionAdvanceJvm:";
    private static final String HMCL_INSTANCE_SETTINGS_SCHEMA =
            "https://schemas.glavo.site/hmcl/instance-game-settings/1.0.0";
    private static final String OFFICIAL_LAUNCHER_PROFILES = "launcher_profiles.json";

    private LaunchProfileInstaller() {
    }

    static Result install(Path gameDirectory, Path owner, Path agent) throws Exception {
        return install(gameDirectory, owner, agent, launchVersion());
    }

    static Result install(Path gameDirectory, List<String> desiredArguments) throws Exception {
        return install(gameDirectory, desiredArguments, launchVersion());
    }

    static Result installWithPreLaunch(Path gameDirectory, List<String> desiredArguments,
                                       String preLaunchCommand) throws Exception {
        return install(gameDirectory, desiredArguments, launchVersion(), preLaunchCommand);
    }

    static Result install(Path gameDirectory, Path owner, Path agent, String versionId) throws Exception {
        return install(gameDirectory, ownedAgentArguments(gameDirectory, owner, agent), versionId);
    }

    static Result install(Path gameDirectory, List<String> desiredArguments, String versionId) throws Exception {
        return install(gameDirectory, desiredArguments, versionId, null);
    }

    static Result install(Path gameDirectory, List<String> desiredArguments, String versionId,
                          String preLaunchCommand) throws Exception {
        String resolvedVersionId = resolveVersionId(gameDirectory, versionId);
        Path pclSetup = gameDirectory.resolve("PCL").resolve("Setup.ini");
        if (Files.isRegularFile(pclSetup)) {
            Path versionJson = locatePclVersionJson(gameDirectory, resolvedVersionId);
            if (versionJson == null) {
                return Result.UNSUPPORTED;
            }
            boolean changed = updateBoth(pclSetup,
                    () -> updatePcl(pclSetup, desiredArguments, preLaunchCommand),
                    versionJson, () -> updateVersionJson(versionJson, desiredArguments));
            return changed ? Result.PCL : Result.PCL_UNCHANGED;
        }

        Path prismConfig = locatePrismConfig(gameDirectory);
        if (prismConfig != null && prismUsesInstanceArguments(prismConfig)) {
            return updatePrism(prismConfig, desiredArguments, preLaunchCommand)
                    ? Result.PRISM : Result.PRISM_UNCHANGED;
        }

        Path hmclSettings = locateHmclSettings(gameDirectory, resolvedVersionId);
        if (hmclSettings != null) {
            Path hmclVersionJson = locateHmclVersionJson(gameDirectory, resolvedVersionId);
            if (hmclVersionJson == null) {
                return Result.UNSUPPORTED;
            }
            boolean changed = updateBoth(hmclSettings,
                    () -> updateHmcl(hmclSettings, desiredArguments, preLaunchCommand),
                    hmclVersionJson, () -> updateVersionJson(hmclVersionJson, desiredArguments));
            return changed ? Result.HMCL : Result.HMCL_UNCHANGED;
        }

        Path officialProfiles = locateOfficialLauncherProfiles(gameDirectory, resolvedVersionId);
        if (officialProfiles != null) {
            Path officialVersionJson = officialProfiles.getParent().resolve("versions")
                    .resolve(resolvedVersionId).resolve(resolvedVersionId + ".json");
            if (!Files.isRegularFile(officialVersionJson)) {
                return Result.UNSUPPORTED;
            }
            boolean changed = updateBoth(officialProfiles,
                    () -> updateOfficialLauncherProfiles(
                            officialProfiles, gameDirectory, resolvedVersionId, desiredArguments),
                    officialVersionJson, () -> updateVersionJson(officialVersionJson, desiredArguments));
            return changed ? Result.OFFICIAL : Result.OFFICIAL_UNCHANGED;
        }

        Path versionJson = locateVersionJson(gameDirectory, resolvedVersionId);
        if (versionJson != null) {
            return updateVersionJson(versionJson, desiredArguments)
                    ? Result.VERSION_JSON : Result.VERSION_JSON_UNCHANGED;
        }
        return Result.UNSUPPORTED;
    }

    static boolean updatePcl(Path setup, Path gameDirectory, Path owner, Path agent) throws Exception {
        return updatePcl(setup, ownedAgentArguments(gameDirectory, owner, agent));
    }

    private static boolean updatePcl(Path setup, List<String> desiredArguments) throws Exception {
        return updatePcl(setup, desiredArguments, null);
    }

    private static boolean updatePcl(Path setup, List<String> desiredArguments,
                                     String preLaunchCommand) throws Exception {
        String content = Files.readString(setup, StandardCharsets.UTF_8);
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(content.lines().toList());
        List<String> quotedArguments = desiredArguments.stream().map(LaunchProfileInstaller::quote).toList();
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
            updated = append(updated, quotedArguments);
            String replacement = PCL_JVM_ARGUMENTS + updated;
            changed = !replacement.equals(line);
            lines.set(index, replacement);
            break;
        }

        if (!found) {
            lines.add(PCL_JVM_ARGUMENTS + append("", quotedArguments));
            changed = true;
        }
        changed |= updateManagedCommand(lines, "VersionAdvanceRun:", "VersionAdvanceRunWait:",
                preLaunchCommand, ":");
        if (!changed) {
            return false;
        }

        String updated = String.join(newline, lines) + (content.endsWith("\n") ? newline : "");
        AtomicFileWriter.write(setup, updated.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    static boolean updateVersionJson(Path file, Path gameDirectory, Path owner, Path agent) throws Exception {
        return updateVersionJson(file, ownedAgentArguments(gameDirectory, owner, agent));
    }

    private static boolean updateVersionJson(Path file, List<String> desiredArguments) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject arguments = root.has("arguments") && root.get("arguments").isJsonObject()
                ? root.getAsJsonObject("arguments")
                : new JsonObject();
        JsonArray current = arguments.has("jvm") && arguments.get("jvm").isJsonArray()
                ? arguments.getAsJsonArray("jvm")
                : new JsonArray();
        JsonArray updated = new JsonArray();
        boolean hasLegacyArchiveArguments = current.asList().stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(LaunchProfileInstaller::isLegacyArchiveArgument);
        Integer managedCompilerCount = null;
        for (JsonElement element : current) {
            String normalized = element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    ? element.getAsString().toLowerCase(Locale.ROOT) : null;
            if (normalized != null && managedCompilerCount != null
                    && StartupThreadBudget.isManagedCompilerArgument(normalized, managedCompilerCount)) {
                managedCompilerCount = null;
                continue;
            }
            managedCompilerCount = normalized == null ? null
                    : StartupThreadBudget.managedCompilerCount(normalized);
            if (managedCompilerCount != null || normalized != null && isOwnedAgentArgument(normalized)
                    || hasLegacyArchiveArguments && isXShareAuto(element)
                    || normalized != null && isLegacyArchiveArgument(normalized)) {
                continue;
            }
            updated.add(element.deepCopy());
        }
        desiredArguments.forEach(updated::add);

        if (current.equals(updated)) {
            return false;
        }
        arguments.add("jvm", updated);
        root.add("arguments", arguments);
        AtomicFileWriter.write(file, (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        return true;
    }

    static boolean updatePrism(Path config, Path gameDirectory, Path owner, Path agent) throws Exception {
        return updatePrism(config, ownedAgentArguments(gameDirectory, owner, agent));
    }

    private static boolean updatePrism(Path config, List<String> desiredArguments) throws Exception {
        return updatePrism(config, desiredArguments, null);
    }

    private static boolean updatePrism(Path config, List<String> desiredArguments,
                                       String preLaunchCommand) throws Exception {
        String content = Files.readString(config, StandardCharsets.UTF_8);
        if (content.lines().noneMatch(line -> line.equals("OverrideJavaArgs=true"))) {
            return false;
        }
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(content.lines().toList());
        List<String> quotedArguments = desiredArguments.stream().map(LaunchProfileInstaller::quote).toList();
        boolean foundArguments = false;
        boolean changed = false;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("JvmArgs=")) {
                foundArguments = true;
                String updated = append(removeLightspeedArguments(line.substring("JvmArgs=".length())),
                        quotedArguments);
                String replacement = "JvmArgs=" + updated;
                changed |= !replacement.equals(line);
                lines.set(index, replacement);
            }
        }
        if (!foundArguments) {
            lines.add("JvmArgs=" + append("", quotedArguments));
            changed = true;
        }
        changed |= updateManagedCommand(lines, "PreLaunchCommand=", "OverrideCommands=",
                preLaunchCommand, "=");
        if (!changed) {
            return false;
        }

        String updated = String.join(newline, lines) + (content.endsWith("\n") ? newline : "");
        AtomicFileWriter.write(config, updated.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private static boolean updateHmcl(Path settingsFile, List<String> desiredArguments,
                                      String preLaunchCommand) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(settingsFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        String existing = root.has("jvmOptions") && root.get("jvmOptions").isJsonPrimitive()
                && root.getAsJsonPrimitive("jvmOptions").isString()
                ? root.get("jvmOptions").getAsString() : "";
        String updated = append(removeLightspeedArguments(existing),
                desiredArguments.stream().map(LaunchProfileInstaller::quote).toList());

        JsonArray overrides = root.has("overrideProperties") && root.get("overrideProperties").isJsonArray()
                ? root.getAsJsonArray("overrideProperties") : new JsonArray();
        boolean hasJvmOverride = overrides.asList().stream()
                .anyMatch(element -> element.isJsonPrimitive()
                        && element.getAsJsonPrimitive().isString()
                        && element.getAsString().equals("jvmOptions"));
        String existingPreLaunch = root.has("preLaunchCommand")
                && root.get("preLaunchCommand").isJsonPrimitive()
                && root.getAsJsonPrimitive("preLaunchCommand").isString()
                ? root.get("preLaunchCommand").getAsString() : "";
        boolean managePreLaunch = preLaunchCommand != null
                && existingPreLaunch.isBlank();
        boolean hasPreLaunchOverride = overrides.asList().stream()
                .anyMatch(element -> element.isJsonPrimitive()
                        && element.getAsJsonPrimitive().isString()
                        && element.getAsString().equals("preLaunchCommand"));
        if (existing.equals(updated) && hasJvmOverride
                && (!managePreLaunch || existingPreLaunch.equals(preLaunchCommand) && hasPreLaunchOverride)) {
            return false;
        }

        root.addProperty("jvmOptions", updated);
        if (!hasJvmOverride) {
            overrides.add("jvmOptions");
        }
        if (managePreLaunch) {
            root.addProperty("preLaunchCommand", preLaunchCommand);
            if (!hasPreLaunchOverride) {
                overrides.add("preLaunchCommand");
            }
        }
        root.add("overrideProperties", overrides);
        AtomicFileWriter.write(settingsFile,
                (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private static boolean updateOfficialLauncherProfiles(Path profilesFile, Path gameDirectory,
                                                           String versionId, List<String> desiredArguments)
            throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(profilesFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject profiles = root.has("profiles") && root.get("profiles").isJsonObject()
                ? root.getAsJsonObject("profiles") : new JsonObject();
        boolean changed = false;
        for (var entry : profiles.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject profile = entry.getValue().getAsJsonObject();
            if (!officialProfileMatches(profile, profilesFile.getParent(), gameDirectory, versionId)) {
                continue;
            }
            String existing = profile.has("javaArgs") && profile.get("javaArgs").isJsonPrimitive()
                    && profile.getAsJsonPrimitive("javaArgs").isString()
                    ? profile.get("javaArgs").getAsString() : "";
            String updated = append(removeLightspeedArguments(existing),
                    desiredArguments.stream().map(LaunchProfileInstaller::quote).toList());
            if (!existing.equals(updated)) {
                profile.addProperty("javaArgs", updated);
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        root.add("profiles", profiles);
        AtomicFileWriter.write(profilesFile,
                (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private static boolean updateManagedCommand(List<String> lines, String commandPrefix, String waitPrefix,
                                                String desiredCommand, String separator) {
        if (desiredCommand == null) {
            return false;
        }
        int commandIndex = -1;
        String existing = "";
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(commandPrefix)) {
                commandIndex = index;
                existing = lines.get(index).substring(commandPrefix.length());
                break;
            }
        }
        if (!existing.isBlank()) {
            return false;
        }
        boolean changed = false;
        String replacement = commandPrefix + desiredCommand;
        if (commandIndex < 0) {
            lines.add(replacement);
            changed = true;
        } else if (!lines.get(commandIndex).equals(replacement)) {
            lines.set(commandIndex, replacement);
            changed = true;
        }
        int waitIndex = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(waitPrefix)) {
                waitIndex = index;
                break;
            }
        }
        String waitLine = waitPrefix + "true";
        if (waitIndex < 0) {
            lines.add(waitLine);
            changed = true;
        } else if (!lines.get(waitIndex).equals(waitLine)) {
            lines.set(waitIndex, waitLine);
            changed = true;
        }
        if (separator.equals("=") && lines.stream().noneMatch(line -> line.equals("OverrideCommands=true"))) {
            lines.add("OverrideCommands=true");
            changed = true;
        }
        return changed;
    }

    private static Path locateHmclVersionJson(Path gameDirectory, String versionId) {
        if (!isSafeVersionId(versionId)) {
            return null;
        }

        Path sharedInstanceRoot = gameDirectory.resolve("versions").resolve(versionId);
        Path sharedManifest = sharedInstanceRoot.resolve(versionId + ".json");
        if (isHmclInstanceRoot(sharedInstanceRoot) && Files.isRegularFile(sharedManifest)) {
            return sharedManifest;
        }

        Path isolatedManifest = gameDirectory.resolve(versionId + ".json");
        if (isHmclInstanceRoot(gameDirectory) && Files.isRegularFile(isolatedManifest)) {
            return isolatedManifest;
        }
        return null;
    }

    private static Path locateHmclSettings(Path gameDirectory, String versionId) {
        if (!isSafeVersionId(versionId)) {
            return null;
        }
        Path sharedInstanceRoot = gameDirectory.resolve("versions").resolve(versionId);
        if (isHmclInstanceRoot(sharedInstanceRoot)) {
            return sharedInstanceRoot.resolve(".hmcl").resolve("config")
                    .resolve("instance-game-settings.json");
        }
        if (isHmclInstanceRoot(gameDirectory)) {
            return gameDirectory.resolve(".hmcl").resolve("config")
                    .resolve("instance-game-settings.json");
        }
        return null;
    }

    private static Path locateOfficialLauncherProfiles(Path gameDirectory, String versionId) {
        if (!isSafeVersionId(versionId)) {
            return null;
        }
        for (Path candidate : officialLauncherProfileCandidates(gameDirectory)) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                JsonObject root = JsonParser.parseString(Files.readString(candidate, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (!root.has("profiles") || !root.get("profiles").isJsonObject()) {
                    continue;
                }
                boolean matches = root.getAsJsonObject("profiles").entrySet().stream()
                        .map(java.util.Map.Entry::getValue)
                        .filter(JsonElement::isJsonObject)
                        .map(JsonElement::getAsJsonObject)
                        .anyMatch(profile -> officialProfileMatches(
                                profile, candidate.getParent(), gameDirectory, versionId));
                if (matches) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // A malformed or locked launcher profile is not safe to update automatically.
            }
        }
        return null;
    }

    private static Set<Path> officialLauncherProfileCandidates(Path gameDirectory) {
        Set<Path> candidates = new LinkedHashSet<>();
        Path current = gameDirectory.toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 5; depth++) {
            candidates.add(current.resolve(OFFICIAL_LAUNCHER_PROFILES));
            current = current.getParent();
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            candidates.add(Path.of(appData).resolve(".minecraft").resolve(OFFICIAL_LAUNCHER_PROFILES));
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            Path home = Path.of(userHome);
            candidates.add(home.resolve(".minecraft").resolve(OFFICIAL_LAUNCHER_PROFILES));
            candidates.add(home.resolve("Library").resolve("Application Support").resolve("minecraft")
                    .resolve(OFFICIAL_LAUNCHER_PROFILES));
        }
        return candidates;
    }

    private static boolean officialProfileMatches(JsonObject profile, Path launcherRoot,
                                                   Path gameDirectory, String versionId) {
        if (!profile.has("lastVersionId") || !profile.get("lastVersionId").isJsonPrimitive()
                || !versionId.equals(profile.get("lastVersionId").getAsString())) {
            return false;
        }
        Path expectedGameDirectory = launcherRoot;
        if (profile.has("gameDir") && profile.get("gameDir").isJsonPrimitive()
                && profile.getAsJsonPrimitive("gameDir").isString()
                && !profile.get("gameDir").getAsString().isBlank()) {
            try {
                expectedGameDirectory = Path.of(profile.get("gameDir").getAsString());
            } catch (RuntimeException exception) {
                return false;
            }
        }
        String expected = expectedGameDirectory.toAbsolutePath().normalize().toString();
        String actual = gameDirectory.toAbsolutePath().normalize().toString();
        return isWindows() ? expected.equalsIgnoreCase(actual) : expected.equals(actual);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path locateVersionJson(Path gameDirectory, String versionId) {
        if (!isSafeVersionId(versionId)) {
            return null;
        }
        Path candidate = gameDirectory.resolve(versionId + ".json");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static Path locatePclVersionJson(Path gameDirectory, String versionId) {
        Path fileName = gameDirectory.toAbsolutePath().normalize().getFileName();
        if (fileName != null && isSafeVersionId(fileName.toString())) {
            Path isolated = gameDirectory.resolve(fileName + ".json");
            if (Files.isRegularFile(isolated)) {
                return isolated;
            }
        }
        return locateVersionJson(gameDirectory, versionId);
    }

    private static boolean isHmclInstanceRoot(Path instanceRoot) {
        Path marker = instanceRoot.resolve(".hmcl").resolve("config").resolve("instance-game-settings.json");
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            JsonObject settings = JsonParser.parseString(Files.readString(marker, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement schema = settings.get("$schema");
            return schema != null && schema.isJsonPrimitive() && schema.getAsJsonPrimitive().isString()
                    && HMCL_INSTANCE_SETTINGS_SCHEMA.equals(schema.getAsString());
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isSafeVersionId(String versionId) {
        if (versionId == null || versionId.isBlank() || versionId.equals(".") || versionId.equals("..")) {
            return false;
        }
        try {
            Path path = Path.of(versionId);
            return path.getNameCount() == 1 && path.getFileName().toString().equals(versionId);
        } catch (RuntimeException exception) {
            return false;
        }
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
        String version = versionFromTokens(List.of(arguments));
        if (version != null) {
            return version;
        }
        for (String argument : arguments) {
            if (!argument.startsWith("@") || argument.length() == 1) {
                continue;
            }
            try {
                Path argumentFile = Path.of(unquote(argument.substring(1)));
                if (Files.isRegularFile(argumentFile) && Files.size(argumentFile) <= 1024 * 1024) {
                    version = versionFromTokens(tokenize(Files.readString(argumentFile, StandardCharsets.UTF_8)));
                    if (version != null) {
                        return version;
                    }
                }
            } catch (Exception ignored) {
                // Continue with the expanded Java command and isolated-directory fallback.
            }
        }
        version = versionFromTokens(tokenize(System.getProperty("sun.java.command", "")));
        if (version != null) {
            return version;
        }
        return ProcessHandle.current().info().commandLine()
                .map(LaunchProfileInstaller::tokenize)
                .map(LaunchProfileInstaller::versionFromTokens)
                .orElse(null);
    }

    private static String resolveVersionId(Path gameDirectory, String versionId) {
        if (isSafeVersionId(versionId)) {
            return versionId;
        }
        String launchedVersion = launchVersion();
        if (isSafeVersionId(launchedVersion)) {
            return launchedVersion;
        }
        Path fileName = gameDirectory.toAbsolutePath().normalize().getFileName();
        if (fileName != null) {
            String isolatedVersion = fileName.toString();
            if (isSafeVersionId(isolatedVersion)
                    && Files.isRegularFile(gameDirectory.resolve(isolatedVersion + ".json"))) {
                return isolatedVersion;
            }
        }
        return null;
    }

    private static String versionFromTokens(List<String> tokens) {
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).equals("--version")) {
                String version = unquote(tokens.get(index + 1));
                return isSafeVersionId(version) ? version : null;
            }
        }
        return null;
    }

    private static String removeLightspeedArguments(String arguments) {
        List<String> tokens = tokenize(arguments);
        boolean hasLegacyArchiveArguments = tokens.stream()
                .map(LaunchProfileInstaller::unquote)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(LaunchProfileInstaller::isLegacyArchiveArgument);
        List<String> retained = new ArrayList<>();
        Integer managedCompilerCount = null;
        for (String token : tokens) {
            String normalized = unquote(token).toLowerCase(Locale.ROOT);
            if (managedCompilerCount != null
                    && StartupThreadBudget.isManagedCompilerArgument(normalized, managedCompilerCount)) {
                managedCompilerCount = null;
                continue;
            }
            managedCompilerCount = StartupThreadBudget.managedCompilerCount(normalized);
            if (managedCompilerCount != null) {
                continue;
            }
            if (isOwnedAgentArgument(normalized)
                    || isLegacyArchiveArgument(normalized)
                    || hasLegacyArchiveArguments && normalized.equals("-xshare:auto")) {
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

    private static String append(String existing, List<String> arguments) {
        String suffix = String.join(" ", arguments);
        return existing.isBlank() ? suffix : existing + ' ' + suffix;
    }

    static String formatArgumentLine(List<String> arguments) {
        return arguments.stream().map(LaunchProfileInstaller::quote)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static List<String> ownedAgentArguments(Path gameDirectory, Path owner, Path agent) {
        return List.of(
                BootstrapAgentInstaller.cacheDirectoryArgument(gameDirectory),
                BootstrapAgentInstaller.ownerArgument(owner),
                BootstrapAgentInstaller.agentArgument(agent));
    }

    private static String quote(String argument) {
        return '"' + argument.replace("\"", "\\\"") + '"';
    }

    private static boolean isXShareAuto(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                && element.getAsString().equalsIgnoreCase("-Xshare:auto");
    }

    private static boolean isOwnedAgentArgument(String value) {
        return value.startsWith("-javaagent:") && value.contains("lightspeed-bootstrap-agent")
                || value.startsWith("-dlightspeed.agent.owner=")
                || value.startsWith("-dlightspeed.bootstrapcachedir=")
                || value.startsWith("-dlightspeed.workers=")
                || value.startsWith("-dlightspeed.managedcicompilercount=");
    }

    private static boolean isLegacyArchiveArgument(String value) {
        return value.startsWith("-dlightspeed.appcds.")
                || (value.startsWith("-xx:archiveclassesatexit=")
                || value.startsWith("-xx:sharedarchivefile="))
                && (value.contains("lightspeed-cache/appcds")
                || value.contains("lightspeed-cache\\appcds"));
    }

    private static boolean updateBoth(Path firstFile, Callable<Boolean> firstUpdate,
                                      Path secondFile, Callable<Boolean> secondUpdate) throws Exception {
        byte[] firstOriginal = Files.readAllBytes(firstFile);
        byte[] secondOriginal = Files.readAllBytes(secondFile);
        try {
            boolean firstChanged = firstUpdate.call();
            boolean secondChanged = secondUpdate.call();
            return firstChanged || secondChanged;
        } catch (Exception failure) {
            restore(firstFile, firstOriginal, failure);
            restore(secondFile, secondOriginal, failure);
            throw failure;
        }
    }

    private static void restore(Path file, byte[] original, Exception failure) {
        try {
            AtomicFileWriter.write(file, original);
        } catch (Throwable rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    enum Result {
        PCL("PCL per-instance and version JSON JVM arguments", true, LauncherFamily.PCL),
        PCL_UNCHANGED("existing PCL JVM arguments", false, LauncherFamily.PCL),
        PRISM("Prism Launcher or MultiMC instance JVM arguments", true, LauncherFamily.PRISM),
        PRISM_UNCHANGED("existing Prism Launcher or MultiMC JVM arguments", false, LauncherFamily.PRISM),
        HMCL("HMCL instance and version JSON JVM arguments", true, LauncherFamily.HMCL),
        HMCL_UNCHANGED("existing HMCL JVM arguments", false, LauncherFamily.HMCL),
        OFFICIAL("official Minecraft Launcher installation JVM arguments", true, LauncherFamily.OFFICIAL),
        OFFICIAL_UNCHANGED("existing official Minecraft Launcher JVM arguments", false, LauncherFamily.OFFICIAL),
        VERSION_JSON("Minecraft version JSON", true, null),
        VERSION_JSON_UNCHANGED("existing Minecraft version JSON", false, null),
        UNSUPPORTED("unsupported launcher configuration", false, null);

        private final String description;
        private final boolean changed;
        private final LauncherFamily launcherFamily;

        Result(String description, boolean changed, LauncherFamily launcherFamily) {
            this.description = description;
            this.changed = changed;
            this.launcherFamily = launcherFamily;
        }

        String description() {
            return description;
        }

        boolean changed() {
            return changed;
        }

        LauncherFamily launcherFamily() {
            return launcherFamily;
        }
    }

    enum LauncherFamily {
        PCL,
        PRISM,
        HMCL,
        OFFICIAL
    }
}
