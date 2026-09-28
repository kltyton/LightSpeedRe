package com.ccr4ft3r.lightspeed.startup.installation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class LaunchProfileInstallerCheck {
    private static final String HMCL_INSTANCE_SETTINGS_SCHEMA =
            "https://schemas.glavo.site/hmcl/instance-game-settings/1.0.0";

    private LaunchProfileInstallerCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("lightspeed-profile-check");
        try {
            Path owner = directory.resolve("mods").resolve("Lightspeed Mod.jar").toAbsolutePath();
            Path agent = directory.resolve("cache").resolve("lightspeed-bootstrap-agent-new.jar").toAbsolutePath();
            Files.createDirectories(owner.getParent());
            Files.createDirectories(agent.getParent());
            Files.writeString(owner, "owner", StandardCharsets.UTF_8);
            Files.writeString(agent, "agent", StandardCharsets.UTF_8);
            checkPclAndVersionJson(directory, owner, agent);
            checkManagedPreLaunchCommands(directory, owner, agent);
            checkPclRollback(directory, owner, agent);
            checkPclMissingVersionJson(directory, owner, agent);
            checkPcl(directory, owner, agent);
            checkVersionJson(directory, owner, agent);
            checkHmclIsolated(directory, owner, agent);
            checkHmclSharedGameDirectory(directory, owner, agent);
            checkHmclRollback(directory, owner, agent);
            checkHmclMarkerRejection(directory, owner, agent);
            checkOfficialLauncher(directory, owner, agent);
            checkOfficialLauncherRollback(directory, owner, agent);
            checkDirectVersionJsonInstall(directory, owner, agent);
            checkPrism(directory, owner, agent);
            checkManualArgumentLine(directory, owner, agent);
            checkStartupThreadBudget();
            System.out.println("LAUNCH_PROFILE_INSTALLER_OK");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    private static void checkPclAndVersionJson(Path directory, Path owner, Path agent) throws Exception {
        String versionId = "pcl-live-profile";
        Path gameDirectory = directory.resolve("pcl-live-profile");
        Path setup = gameDirectory.resolve("PCL").resolve("Setup.ini");
        Path manifest = gameDirectory.resolve(versionId + ".json");
        Files.createDirectories(setup.getParent());
        Files.writeString(setup, "VersionAdvanceJvm:-Xmx8G\r\n", StandardCharsets.UTF_8);
        Files.writeString(manifest, "{\"arguments\":{\"jvm\":[\"-Xms2G\"]}}", StandardCharsets.UTF_8);

        require(LaunchProfileInstaller.install(gameDirectory,
                        java.util.List.of(
                                BootstrapAgentInstaller.cacheDirectoryArgument(gameDirectory),
                                BootstrapAgentInstaller.ownerArgument(owner),
                                BootstrapAgentInstaller.agentArgument(agent)), "1.20.1")
                        == LaunchProfileInstaller.Result.PCL,
                "PCL installation must prefer its isolated directory when launch metadata reports another version");
        require(Files.readString(setup, StandardCharsets.UTF_8)
                        .contains(BootstrapAgentInstaller.agentArgument(agent)),
                "PCL setup did not receive the Agent argument");
        require(readJvmArguments(manifest).contains(
                        new JsonPrimitive(BootstrapAgentInstaller.agentArgument(agent))),
                "PCL fallback version JSON did not receive the Agent argument");
    }

    private static void checkPclRollback(Path directory, Path owner, Path agent) throws Exception {
        String versionId = "pcl-rollback";
        Path gameDirectory = directory.resolve(versionId);
        Path setup = gameDirectory.resolve("PCL").resolve("Setup.ini");
        Path manifest = gameDirectory.resolve(versionId + ".json");
        Files.createDirectories(setup.getParent());
        byte[] originalSetup = "VersionAdvanceJvm:-Xmx8G\r\nOther:1\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] originalManifest = "{malformed-version-json".getBytes(StandardCharsets.UTF_8);
        Files.write(setup, originalSetup);
        Files.write(manifest, originalManifest);

        Exception failure = expectInstallFailure(gameDirectory, owner, agent, versionId,
                "malformed PCL version JSON must fail installation");
        require(failure instanceof JsonSyntaxException, "PCL rollback must rethrow the original update failure");
        require(java.util.Arrays.equals(originalSetup, Files.readAllBytes(setup)),
                "PCL Setup.ini must be restored byte-for-byte when version JSON update fails");
        require(java.util.Arrays.equals(originalManifest, Files.readAllBytes(manifest)),
                "failed PCL version JSON must remain byte-for-byte original");
    }

    private static void checkManagedPreLaunchCommands(Path directory, Path owner, Path agent) throws Exception {
        String command = "cmd.exe /d /s /c call \"C:\\Game Path\\pack-compiler.cmd\"";

        String pclVersion = "pcl-prelaunch";
        Path pclGame = directory.resolve(pclVersion);
        Path pclSetup = pclGame.resolve("PCL").resolve("Setup.ini");
        Path pclManifest = pclGame.resolve(pclVersion + ".json");
        Files.createDirectories(pclSetup.getParent());
        Files.writeString(pclSetup, "VersionAdvanceJvm:-Xmx8G\r\n", StandardCharsets.UTF_8);
        Files.writeString(pclManifest, versionJsonWithStructuredArgument(), StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.install(pclGame,
                        java.util.List.of(BootstrapAgentInstaller.agentArgument(agent)), pclVersion, command)
                        == LaunchProfileInstaller.Result.PCL,
                "PCL managed pre-launch command was not installed");
        String pclText = Files.readString(pclSetup, StandardCharsets.UTF_8);
        require(pclText.contains("VersionAdvanceRun:" + command)
                        && pclText.contains("VersionAdvanceRunWait:true"),
                "PCL pre-launch command or wait contract is missing");
        require(LaunchProfileInstaller.install(pclGame,
                        java.util.List.of(BootstrapAgentInstaller.agentArgument(agent)), pclVersion, command)
                        == LaunchProfileInstaller.Result.PCL_UNCHANGED,
                "PCL managed pre-launch command is not idempotent");

        String userVersion = "pcl-user-prelaunch";
        Path userGame = directory.resolve(userVersion);
        Path userSetup = userGame.resolve("PCL").resolve("Setup.ini");
        Files.createDirectories(userSetup.getParent());
        Files.writeString(userSetup,
                "VersionAdvanceJvm:-Xmx8G\nVersionAdvanceRun:echo user-command\n",
                StandardCharsets.UTF_8);
        Files.writeString(userGame.resolve(userVersion + ".json"),
                versionJsonWithStructuredArgument(), StandardCharsets.UTF_8);
        LaunchProfileInstaller.install(userGame,
                java.util.List.of(BootstrapAgentInstaller.agentArgument(agent)), userVersion, command);
        require(Files.readString(userSetup, StandardCharsets.UTF_8)
                        .contains("VersionAdvanceRun:echo user-command"),
                "PCL user pre-launch command was overwritten");

        String hmclVersion = "hmcl-prelaunch";
        Path hmclGame = directory.resolve(hmclVersion);
        Path hmclRoot = hmclGame.resolve("versions").resolve(hmclVersion);
        Path hmclSettings = createHmclMarker(hmclRoot);
        Path hmclManifest = hmclRoot.resolve(hmclVersion + ".json");
        Files.writeString(hmclManifest, versionJsonWithStructuredArgument(), StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.install(hmclGame,
                        java.util.List.of(BootstrapAgentInstaller.agentArgument(agent)), hmclVersion, command)
                        == LaunchProfileInstaller.Result.HMCL,
                "HMCL managed pre-launch command was not installed");
        JsonObject hmcl = JsonParser.parseString(Files.readString(hmclSettings, StandardCharsets.UTF_8))
                .getAsJsonObject();
        require(command.equals(hmcl.get("preLaunchCommand").getAsString())
                        && hmcl.getAsJsonArray("overrideProperties")
                        .contains(new JsonPrimitive("preLaunchCommand")),
                "HMCL pre-launch override is incomplete");

        Path prismGame = directory.resolve("prism-prelaunch");
        Files.createDirectories(prismGame);
        Path prism = prismGame.resolve("instance.cfg");
        Files.writeString(prism, "OverrideJavaArgs=true\nJvmArgs=-Xmx8G\n", StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.install(prismGame,
                        java.util.List.of(BootstrapAgentInstaller.agentArgument(agent)), "prism-prelaunch", command)
                        == LaunchProfileInstaller.Result.PRISM,
                "Prism managed pre-launch command was not installed");
        String prismText = Files.readString(prism, StandardCharsets.UTF_8);
        require(prismText.contains("PreLaunchCommand=" + command)
                        && prismText.contains("OverrideCommands=true"),
                "Prism pre-launch command or override is incomplete");
    }

    private static void checkPcl(Path directory, Path owner, Path agent) throws Exception {
        Path setup = directory.resolve("Setup.ini");
        Files.writeString(setup, "VersionAdvanceJvm:-Xmx8G -javaagent:D:\\old\\lightspeed-bootstrap-agent-old.jar "
                        + "-Dlightspeed.workers=7 -Dlightspeed.managedCICompilerCount=5 "
                        + "-XX:CICompilerCount=5 -XX:CICompilerCount=3 "
                        + "-Dlightspeed.appCds.active=true -Xshare:auto "
                        + "-XX:SharedArchiveFile=D:\\old\\lightspeed-cache\\appcds\\old.jsa\r\nOther:1\r\n",
                StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.updatePcl(setup, directory, owner, agent), "PCL profile must change");
        String updated = Files.readString(setup, StandardCharsets.UTF_8);
        require(updated.contains("-Xmx8G"), "unrelated JVM arguments must be preserved");
        require(!updated.contains("lightspeed-bootstrap-agent-old.jar"), "stale bootstrap path must be removed");
        require(!updated.contains("lightspeed.appCds"), "legacy PCL archive marker must be removed");
        require(!updated.contains("old.jsa"), "stale PCL archive must be removed");
        require(!updated.contains("-Xshare:auto"), "Lightspeed-owned stale PCL sharing flag must be removed");
        require(!updated.contains("-Dlightspeed.workers=7"), "stale managed worker budget must be removed");
        require(!updated.contains("-Dlightspeed.managedCICompilerCount=5"),
                "stale compiler budget marker must be removed");
        require(!updated.contains("-XX:CICompilerCount=5"), "stale managed compiler budget must be removed");
        require(updated.contains("-XX:CICompilerCount=3"), "user compiler override must remain");
        require(updated.contains(BootstrapAgentInstaller.ownerArgument(owner)), "owner guard must be installed");
        require(updated.contains(BootstrapAgentInstaller.agentArgument(agent)), "embedded Agent path must be installed");
        require(!Files.exists(setup.resolveSibling("Setup.ini.lightspeed-backup")),
                "PCL update must not create a backup");
        require(updated.contains(BootstrapAgentInstaller.cacheDirectoryArgument(directory)), "PCL cache path must be installed");
        require(!LaunchProfileInstaller.updatePcl(setup, directory, owner, agent), "identical PCL update must be idempotent");
    }

    private static void checkPclMissingVersionJson(Path directory, Path owner, Path agent) throws Exception {
        Path gameDirectory = directory.resolve("pcl-missing-version-json");
        Path setup = gameDirectory.resolve("PCL").resolve("Setup.ini");
        Files.createDirectories(setup.getParent());
        Files.writeString(setup, "VersionAdvanceJvm:-Xmx8G\n", StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.install(gameDirectory, owner, agent, "pcl-missing-version-json")
                        == LaunchProfileInstaller.Result.UNSUPPORTED,
                "PCL must require manual advice when its version JSON cannot also be updated");
    }

    private static void checkVersionJson(Path directory, Path owner, Path agent) throws Exception {
        Path json = directory.resolve("version.json");
        Files.writeString(json, "{\"arguments\":{\"jvm\":[\"-Xmx8G\","
                        + "\"-javaagent:C:/old/lightspeed-bootstrap-agent-old.jar\","
                        + "\"-Dlightspeed.workers=7\",\"-Dlightspeed.managedCICompilerCount=5\","
                        + "\"-XX:CICompilerCount=5\",\"-XX:CICompilerCount=3\","
                        + "\"-Dlightspeed.appCds.active=true\",\"-Xshare:auto\","
                        + "\"-XX:SharedArchiveFile=C:/old/lightspeed-cache/appcds/old.jsa\"]}}",
                StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.updateVersionJson(json, directory, owner, agent), "version JSON must change");
        JsonObject root = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray arguments = root.getAsJsonObject("arguments").getAsJsonArray("jvm");
        require(arguments.contains(new JsonPrimitive("-Xmx8G")), "unrelated JSON arguments must remain");
        require(arguments.contains(new JsonPrimitive("-XX:CICompilerCount=3")),
                "user JSON compiler override must remain");
        require(!arguments.contains(new JsonPrimitive("-XX:CICompilerCount=5")),
                "managed JSON compiler budget must be removed");
        require(arguments.contains(new JsonPrimitive(BootstrapAgentInstaller.ownerArgument(owner))),
                "JSON owner guard must be installed");
        require(arguments.contains(new JsonPrimitive(BootstrapAgentInstaller.agentArgument(agent))),
                "JSON Agent path must be installed");
        require(arguments.asList().stream().noneMatch(LaunchProfileInstallerCheck::isLegacyArchiveArgument),
                "legacy JSON archive arguments must be removed");
        require(!Files.exists(json.resolveSibling("version.json.lightspeed-backup")),
                "JSON update must not create a backup");
        require(!LaunchProfileInstaller.updateVersionJson(json, directory, owner, agent), "identical JSON update must be idempotent");
    }

    private static void checkPrism(Path directory, Path owner, Path agent) throws Exception {
        Path config = directory.resolve("instance.cfg");
        Files.writeString(config, "JvmArgs=-XX:+UseG1GC -javaagent:C:/old/lightspeed-bootstrap-agent-old.jar "
                        + "-Dlightspeed.appCds.training=true -Xshare:auto "
                        + "-XX:ArchiveClassesAtExit=C:/old/lightspeed-cache/appcds/old.jsa\n"
                        + "OverrideJavaArgs=true\n",
                StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.updatePrism(config, directory, owner, agent), "Prism profile must change");
        String updated = Files.readString(config, StandardCharsets.UTF_8);
        require(updated.contains("-XX:+UseG1GC"), "Prism JVM arguments must be preserved");
        require(updated.contains("OverrideJavaArgs=true"), "Prism per-instance override must be enabled");
        require(!updated.contains("lightspeed-bootstrap-agent-old.jar"), "stale Prism bootstrap path must be removed");
        require(!updated.contains("lightspeed.appCds"), "legacy Prism archive marker must be removed");
        require(!updated.contains("old.jsa"), "stale Prism archive must be removed");
        require(!updated.contains("-Xshare:auto"), "Lightspeed-owned stale Prism sharing flag must be removed");
        require(updated.contains(BootstrapAgentInstaller.agentArgument(agent)), "Prism Agent path must be installed");
        require(!LaunchProfileInstaller.updatePrism(config, directory, owner, agent), "identical Prism update must be idempotent");

        Path inherited = directory.resolve("inherited-instance.cfg");
        Files.writeString(inherited, "JvmArgs=\nOverrideJavaArgs=false\n", StandardCharsets.UTF_8);
        require(!LaunchProfileInstaller.updatePrism(inherited, directory, owner, agent),
                "Prism global JVM arguments must not be replaced by an instance override");
        require(Files.readString(inherited, StandardCharsets.UTF_8).contains("OverrideJavaArgs=false"),
                "Prism inherited configuration must remain unchanged");
    }

    private static void checkHmclIsolated(Path directory, Path owner, Path agent) throws Exception {
        String versionId = "forge-1.20.1-hmcl-isolated";
        Path gameDirectory = directory.resolve("hmcl-isolated");
        Path marker = createHmclMarker(gameDirectory);
        String markerContent = Files.readString(marker, StandardCharsets.UTF_8);
        Path manifest = gameDirectory.resolve(versionId + ".json");
        Files.writeString(manifest, versionJsonWithStructuredArgument(), StandardCharsets.UTF_8);

        LaunchProfileInstaller.Result result = LaunchProfileInstaller.install(
                gameDirectory, owner, agent, versionId);
        require(result == LaunchProfileInstaller.Result.HMCL, "isolated HMCL layout must be recognized explicitly");
        require(result.description().contains("HMCL"), "HMCL result must have a distinct description");
        require(!Files.readString(marker, StandardCharsets.UTF_8).equals(markerContent),
                "HMCL instance settings must receive the Agent arguments");
        assertStringArguments(Files.readString(marker, StandardCharsets.UTF_8), gameDirectory, owner, agent);
        assertVersionJsonArguments(manifest, gameDirectory, owner, agent);

        Path backup = manifest.resolveSibling(manifest.getFileName() + ".lightspeed-backup");
        require(!Files.exists(backup), "HMCL update must not create a backup");
        require(LaunchProfileInstaller.install(gameDirectory, owner, agent, versionId)
                        == LaunchProfileInstaller.Result.HMCL_UNCHANGED,
                "identical isolated HMCL installation must be idempotent");
        require(!Files.exists(backup), "idempotent HMCL installation must not create a backup");
    }

    private static void checkHmclSharedGameDirectory(Path directory, Path owner, Path agent) throws Exception {
        String versionId = "forge-1.20.1-hmcl-shared";
        Path gameDirectory = directory.resolve("hmcl-shared");
        Path instanceRoot = gameDirectory.resolve("versions").resolve(versionId);
        Path marker = createHmclMarker(instanceRoot);
        String markerContent = Files.readString(marker, StandardCharsets.UTF_8);
        Path manifest = instanceRoot.resolve(versionId + ".json");
        Files.writeString(manifest, versionJsonWithStructuredArgument(), StandardCharsets.UTF_8);

        require(LaunchProfileInstaller.install(gameDirectory, owner, agent, versionId)
                        == LaunchProfileInstaller.Result.HMCL,
                "shared game-directory HMCL layout must be recognized explicitly");
        require(!Files.readString(marker, StandardCharsets.UTF_8).equals(markerContent),
                "shared HMCL instance settings must receive the Agent arguments");
        assertStringArguments(Files.readString(marker, StandardCharsets.UTF_8), gameDirectory, owner, agent);
        assertVersionJsonArguments(manifest, gameDirectory, owner, agent);
    }

    private static void checkHmclRollback(Path directory, Path owner, Path agent) throws Exception {
        String versionId = "forge-1.20.1-hmcl-rollback";
        Path gameDirectory = directory.resolve("hmcl-rollback");
        Path settings = createHmclMarker(gameDirectory);
        Path manifest = gameDirectory.resolve(versionId + ".json");
        byte[] originalSettings = Files.readAllBytes(settings);
        byte[] originalManifest = "{malformed-version-json".getBytes(StandardCharsets.UTF_8);
        Files.write(manifest, originalManifest);

        Exception failure = expectInstallFailure(gameDirectory, owner, agent, versionId,
                "malformed HMCL version JSON must fail installation");
        require(failure instanceof JsonSyntaxException, "HMCL rollback must rethrow the original update failure");
        require(java.util.Arrays.equals(originalSettings, Files.readAllBytes(settings)),
                "HMCL settings must be restored byte-for-byte when version JSON update fails");
        require(java.util.Arrays.equals(originalManifest, Files.readAllBytes(manifest)),
                "failed HMCL version JSON must remain byte-for-byte original");
    }

    private static void checkOfficialLauncher(Path directory, Path owner, Path agent) throws Exception {
        String versionId = "forge-1.20.1-official";
        Path gameDirectory = directory.resolve("official-launcher").resolve(".minecraft");
        Path manifest = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{\"arguments\":{\"jvm\":[\"-Xms2G\"]}}", StandardCharsets.UTF_8);
        Path profiles = gameDirectory.resolve("launcher_profiles.json");
        Files.writeString(profiles, "{\"profiles\":{"
                        + "\"target\":{\"lastVersionId\":\"" + versionId + "\","
                        + "\"javaArgs\":\"-Xmx8G -javaagent:C:/stale/lightspeed-bootstrap-agent-old.jar\"},"
                        + "\"other\":{\"lastVersionId\":\"latest-release\",\"javaArgs\":\"-Xmx4G\"}},"
                        + "\"clientToken\":\"preserve-me\"}", StandardCharsets.UTF_8);

        require(LaunchProfileInstaller.install(gameDirectory, owner, agent, versionId)
                        == LaunchProfileInstaller.Result.OFFICIAL,
                "official launcher profile must be recognized explicitly");
        JsonObject root = JsonParser.parseString(Files.readString(profiles, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject target = root.getAsJsonObject("profiles").getAsJsonObject("target");
        assertStringArguments(target.get("javaArgs").getAsString(), gameDirectory, owner, agent);
        require(root.get("clientToken").getAsString().equals("preserve-me"),
                "official launcher unrelated root data must remain unchanged");
        require(root.getAsJsonObject("profiles").getAsJsonObject("other").get("javaArgs")
                        .getAsString().equals("-Xmx4G"),
                "unrelated official launcher profiles must remain unchanged");
        JsonArray manifestArguments = readJvmArguments(manifest);
        require(manifestArguments.contains(new JsonPrimitive("-Xms2G")),
                "official launcher version JVM arguments must be preserved");
        require(manifestArguments.contains(new JsonPrimitive(
                        BootstrapAgentInstaller.cacheDirectoryArgument(gameDirectory))),
                "official launcher version cache argument must be installed");
        require(manifestArguments.contains(new JsonPrimitive(BootstrapAgentInstaller.ownerArgument(owner))),
                "official launcher version owner argument must be installed");
        require(manifestArguments.contains(new JsonPrimitive(BootstrapAgentInstaller.agentArgument(agent))),
                "official launcher version Agent argument must be installed");
        require(!Files.exists(profiles.resolveSibling("launcher_profiles.json.lightspeed-backup")),
                "official launcher update must not create a backup");
        require(LaunchProfileInstaller.install(gameDirectory, owner, agent, versionId)
                        == LaunchProfileInstaller.Result.OFFICIAL_UNCHANGED,
                "official launcher update must be idempotent");
    }

    private static void checkOfficialLauncherRollback(Path directory, Path owner, Path agent) throws Exception {
        String versionId = "forge-1.20.1-official-rollback";
        Path gameDirectory = directory.resolve("official-rollback").resolve(".minecraft");
        Path manifest = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        Files.createDirectories(manifest.getParent());
        byte[] originalManifest = "{malformed-version-json".getBytes(StandardCharsets.UTF_8);
        Files.write(manifest, originalManifest);
        Path profiles = gameDirectory.resolve("launcher_profiles.json");
        byte[] originalProfiles = ("{\"profiles\":{\"target\":{\"lastVersionId\":\"" + versionId
                + "\",\"javaArgs\":\"-Xmx8G\"}},\"clientToken\":\"preserve-me\"}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(profiles, originalProfiles);

        Exception failure = expectInstallFailure(gameDirectory, owner, agent, versionId,
                "malformed official version JSON must fail installation");
        require(failure instanceof JsonSyntaxException,
                "official launcher rollback must rethrow the original update failure");
        require(java.util.Arrays.equals(originalProfiles, Files.readAllBytes(profiles)),
                "official launcher_profiles.json must be restored byte-for-byte when version JSON update fails");
        require(java.util.Arrays.equals(originalManifest, Files.readAllBytes(manifest)),
                "failed official version JSON must remain byte-for-byte original");
    }

    private static void checkManualArgumentLine(Path directory, Path owner, Path agent) {
        String line = LaunchProfileInstaller.formatArgumentLine(java.util.List.of(
                BootstrapAgentInstaller.cacheDirectoryArgument(directory),
                BootstrapAgentInstaller.ownerArgument(owner),
                BootstrapAgentInstaller.agentArgument(agent)));
        require(line.contains("\"-Dlightspeed.bootstrapCacheDir="),
                "copied cache argument must be quoted as one JVM token");
        require(line.contains("\"-Dlightspeed.agent.owner="),
                "copied owner argument must be quoted as one JVM token");
        require(line.contains("\"-javaagent:"),
                "copied Agent argument must be quoted as one JVM token");
        require(!line.contains("\n") && !line.contains("\r"),
                "copied JVM arguments must be a single launcher-ready line");
    }

    private static void checkStartupThreadBudget() {
        require(StartupThreadBudget.arguments(
                        "OpenJDK 64-Bit Server VM", "Zulu21.32+17-CA", 16).equals(java.util.List.of(
                        "-Dlightspeed.workers=6",
                        "-Dlightspeed.managedCICompilerCount=4",
                        "-XX:CICompilerCount=4")),
                "16-thread HotSpot budget must match the measured 6/4 optimum");
        require(StartupThreadBudget.arguments(
                        "OpenJDK 64-Bit Server VM", "17.0.11+7-LTS", 8).equals(java.util.List.of(
                        "-Dlightspeed.workers=3",
                        "-Dlightspeed.managedCICompilerCount=2",
                        "-XX:CICompilerCount=2")),
                "8-thread HotSpot budget must retain capacity for Forge and the render thread");
        require(StartupThreadBudget.arguments(
                        "Java HotSpot(TM) 64-Bit Server VM", "21.0.4-jvmci-23.1-b41", 16).isEmpty(),
                "GraalVM must retain its JVMCI compiler ergonomics");
        require(StartupThreadBudget.arguments(
                        "Eclipse OpenJ9 VM", "openj9-0.48.0", 16).isEmpty(),
                "unsupported non-HotSpot runtimes must not receive HotSpot flags");
    }

    private static void checkHmclMarkerRejection(Path directory, Path owner, Path agent) throws Exception {
        String missingVersion = "forge-1.20.1-hmcl-missing-marker";
        Path missingGameDirectory = directory.resolve("hmcl-missing-marker");
        Path missingManifest = missingGameDirectory.resolve("versions").resolve(missingVersion)
                .resolve(missingVersion + ".json");
        Files.createDirectories(missingManifest.getParent());
        Files.writeString(missingManifest, versionJsonWithStructuredArgument(), StandardCharsets.UTF_8);
        String missingOriginal = Files.readString(missingManifest, StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.install(missingGameDirectory, owner, agent, missingVersion)
                        == LaunchProfileInstaller.Result.UNSUPPORTED,
                "HMCL manifest without an instance marker must be rejected");
        require(Files.readString(missingManifest, StandardCharsets.UTF_8).equals(missingOriginal),
                "rejected HMCL manifest must remain unchanged");

        String wrongSchemaVersion = "forge-1.20.1-hmcl-wrong-schema";
        Path wrongSchemaGameDirectory = directory.resolve("hmcl-wrong-schema");
        Path wrongSchemaRoot = wrongSchemaGameDirectory.resolve("versions").resolve(wrongSchemaVersion);
        Path wrongSchemaMarker = wrongSchemaRoot.resolve(".hmcl").resolve("config")
                .resolve("instance-game-settings.json");
        Files.createDirectories(wrongSchemaMarker.getParent());
        Files.writeString(wrongSchemaMarker,
                "{\"$schema\":\"https://schemas.glavo.site/hmcl/instance-game-settings/0.9.0\"}",
                StandardCharsets.UTF_8);
        Path wrongSchemaManifest = wrongSchemaRoot.resolve(wrongSchemaVersion + ".json");
        Files.writeString(wrongSchemaManifest, versionJsonWithStructuredArgument(), StandardCharsets.UTF_8);
        String wrongSchemaOriginal = Files.readString(wrongSchemaManifest, StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.install(wrongSchemaGameDirectory, owner, agent, wrongSchemaVersion)
                        == LaunchProfileInstaller.Result.UNSUPPORTED,
                "HMCL marker with a wrong schema must be rejected");
        require(Files.readString(wrongSchemaManifest, StandardCharsets.UTF_8).equals(wrongSchemaOriginal),
                "wrong-schema HMCL manifest must remain unchanged");

        String malformedVersion = "forge-1.20.1-hmcl-malformed-marker";
        Path malformedGameDirectory = directory.resolve("hmcl-malformed-marker");
        Path malformedRoot = malformedGameDirectory.resolve("versions").resolve(malformedVersion);
        Path malformedMarker = malformedRoot.resolve(".hmcl").resolve("config")
                .resolve("instance-game-settings.json");
        Files.createDirectories(malformedMarker.getParent());
        Files.writeString(malformedMarker, "{not-json", StandardCharsets.UTF_8);
        Path malformedManifest = malformedRoot.resolve(malformedVersion + ".json");
        Files.writeString(malformedManifest, versionJsonWithStructuredArgument(), StandardCharsets.UTF_8);
        String malformedOriginal = Files.readString(malformedManifest, StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.install(malformedGameDirectory, owner, agent, malformedVersion)
                        == LaunchProfileInstaller.Result.UNSUPPORTED,
                "HMCL manifest with a malformed instance marker must be rejected");
        require(Files.readString(malformedManifest, StandardCharsets.UTF_8).equals(malformedOriginal),
                "malformed-marker HMCL manifest must remain unchanged");
        require(!Files.exists(malformedManifest.resolveSibling(malformedManifest.getFileName()
                        + ".lightspeed-backup")),
                "rejected HMCL manifest must not create a backup");

        Path arbitraryGameDirectory = directory.resolve("hmcl-arbitrary-run-directory");
        Files.createDirectories(arbitraryGameDirectory);
        require(LaunchProfileInstaller.install(arbitraryGameDirectory, owner, agent, malformedVersion)
                        == LaunchProfileInstaller.Result.UNSUPPORTED,
                "arbitrary HMCL run directory must remain unsupported without a validated instance root");
    }

    private static void checkDirectVersionJsonInstall(Path directory, Path owner, Path agent) throws Exception {
        String versionId = "forge-1.20.1-direct-json";
        Path gameDirectory = directory.resolve("direct-version-json");
        Files.createDirectories(gameDirectory);
        Path manifest = gameDirectory.resolve(versionId + ".json");
        Files.writeString(manifest, "{\"arguments\":{\"jvm\":[\"-Xms1G\"]}}", StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.install(gameDirectory, owner, agent, versionId)
                        == LaunchProfileInstaller.Result.VERSION_JSON,
                "direct isolated version JSON behavior must remain available without an HMCL marker");
        require(readJvmArguments(manifest).contains(new JsonPrimitive("-Xms1G")),
                "direct version JSON arguments must remain preserved");
    }

    private static Path createHmclMarker(Path instanceRoot) throws Exception {
        Path marker = instanceRoot.resolve(".hmcl").resolve("config").resolve("instance-game-settings.json");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "{\"$schema\":\"" + HMCL_INSTANCE_SETTINGS_SCHEMA
                + "\",\"jvmOptions\":\"-Xmx6G\",\"usesGlobal\":false}\n", StandardCharsets.UTF_8);
        return marker;
    }

    private static void assertStringArguments(String value, Path gameDirectory, Path owner, Path agent) {
        String arguments = value;
        if (value.stripLeading().startsWith("{")) {
            arguments = JsonParser.parseString(value).getAsJsonObject().get("jvmOptions").getAsString();
        }
        require(arguments.contains("-Xmx"),
                "pre-existing launcher JVM configuration must remain present");
        require(arguments.contains(BootstrapAgentInstaller.cacheDirectoryArgument(gameDirectory)),
                "launcher cache argument must be installed");
        require(arguments.contains(BootstrapAgentInstaller.ownerArgument(owner)),
                "launcher owner argument must be installed");
        require(arguments.contains(BootstrapAgentInstaller.agentArgument(agent)),
                "launcher Agent argument must be installed");
        require(!arguments.contains("lightspeed-bootstrap-agent-old.jar"),
                "stale launcher Agent argument must be removed");
    }

    private static String versionJsonWithStructuredArgument() {
        return "{\"arguments\":{\"game\":[\"--demo\"],\"jvm\":["
                + "\"-Xmx8G\","
                + "{\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}}],"
                + "\"value\":[\"-Dexample=true\",\"-Xms2G\"]},"
                + "\"-Dlightspeed.bootstrapCacheDir=C:/stale\","
                + "\"-Dlightspeed.agent.owner=C:/stale/Lightspeed.jar\","
                + "\"-javaagent:C:/stale/lightspeed-bootstrap-agent-old.jar\","
                + "\"-Dlightspeed.appCds.active=true\",\"-Xshare:auto\","
                + "\"-XX:SharedArchiveFile=C:/stale/lightspeed-cache/appcds/old.jsa\"]}}";
    }

    private static void assertVersionJsonArguments(Path manifest, Path gameDirectory, Path owner, Path agent)
            throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray arguments = root.getAsJsonObject("arguments").getAsJsonArray("jvm");
        JsonElement structured = JsonParser.parseString("{\"rules\":[{\"action\":\"allow\","
                + "\"os\":{\"name\":\"windows\"}}],\"value\":[\"-Dexample=true\",\"-Xms2G\"]}");
        require(arguments.contains(new JsonPrimitive("-Xmx8G")), "existing HMCL JVM string must be preserved");
        require(arguments.contains(structured), "structured HMCL JVM entry must be preserved exactly");
        require(root.getAsJsonObject("arguments").getAsJsonArray("game").contains(new JsonPrimitive("--demo")),
                "non-JVM version arguments must be preserved");
        require(arguments.contains(new JsonPrimitive(BootstrapAgentInstaller.cacheDirectoryArgument(gameDirectory))),
                "current HMCL cache argument must be installed");
        require(arguments.contains(new JsonPrimitive(BootstrapAgentInstaller.ownerArgument(owner))),
                "current HMCL owner argument must be installed");
        require(arguments.contains(new JsonPrimitive(BootstrapAgentInstaller.agentArgument(agent))),
                "current HMCL Agent argument must be installed");
        require(arguments.asList().stream()
                        .filter(JsonElement::isJsonPrimitive)
                        .map(JsonElement::getAsString)
                        .noneMatch(value -> value.contains("C:/stale")),
                "only current Lightspeed-owned arguments may remain");
        require(arguments.asList().stream().noneMatch(LaunchProfileInstallerCheck::isLegacyArchiveArgument),
                "HMCL manifest must remove legacy Lightspeed archive arguments");
    }

    private static boolean isLegacyArchiveArgument(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("appcds")
                || normalized.startsWith("-xshare:")
                || normalized.startsWith("-xx:archiveclassesatexit=")
                || normalized.startsWith("-xx:sharedarchivefile=");
    }

    private static boolean isLegacyArchiveArgument(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                && isLegacyArchiveArgument(element.getAsString());
    }

    private static JsonArray readJvmArguments(Path manifest) throws Exception {
        return JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonObject("arguments").getAsJsonArray("jvm");
    }

    private static Exception expectInstallFailure(Path gameDirectory, Path owner, Path agent,
                                                  String versionId, String message) throws Exception {
        try {
            LaunchProfileInstaller.install(gameDirectory, owner, agent, versionId);
        } catch (Exception exception) {
            return exception;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
