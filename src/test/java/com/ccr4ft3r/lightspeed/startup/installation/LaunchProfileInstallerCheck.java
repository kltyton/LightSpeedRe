package com.ccr4ft3r.lightspeed.startup.installation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class LaunchProfileInstallerCheck {
    private LaunchProfileInstallerCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("lightspeed-profile-check");
        try {
            Path owner = directory.resolve("mods").resolve("Lightspeed Mod.jar").toAbsolutePath();
            Path agent = directory.resolve("cache").resolve("lightspeed-bootstrap-agent-new.jar").toAbsolutePath();
            checkPcl(directory, owner, agent);
            checkPrism(directory, owner, agent);
            checkVersionJson(directory, owner, agent);
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

    private static void checkPcl(Path directory, Path owner, Path agent) throws Exception {
        Path setup = directory.resolve("Setup.ini");
        Files.writeString(setup, "VersionAdvanceJvm:-Xmx8G -javaagent:D:\\old\\lightspeed-bootstrap-agent-old.jar\r\nOther:1\r\n",
                StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.updatePcl(setup, directory, owner, agent), "PCL profile must change");
        String updated = Files.readString(setup, StandardCharsets.UTF_8);
        require(updated.contains("-Xmx8G"), "unrelated JVM arguments must be preserved");
        require(!updated.contains("lightspeed-bootstrap-agent-old.jar"), "stale bootstrap path must be removed");
        require(updated.contains(BootstrapAgentInstaller.ownerArgument(owner)), "owner guard must be installed");
        require(updated.contains(BootstrapAgentInstaller.agentArgument(agent)), "embedded Agent path must be installed");
        require(Files.isRegularFile(setup.resolveSibling("Setup.ini.lightspeed-backup")), "PCL backup must exist");
        require(updated.contains(BootstrapAgentInstaller.cacheDirectoryArgument(directory)), "PCL cache path must be installed");
        require(!LaunchProfileInstaller.updatePcl(setup, directory, owner, agent), "identical PCL update must be idempotent");
    }

    private static void checkVersionJson(Path directory, Path owner, Path agent) throws Exception {
        Path json = directory.resolve("version.json");
        Files.writeString(json, "{\"arguments\":{\"jvm\":[\"-Xmx8G\",\"-javaagent:C:/old/lightspeed-bootstrap-agent-old.jar\"]}}",
                StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.updateVersionJson(json, directory, owner, agent), "version JSON must change");
        JsonObject root = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray arguments = root.getAsJsonObject("arguments").getAsJsonArray("jvm");
        require(arguments.contains(new com.google.gson.JsonPrimitive("-Xmx8G")), "unrelated JSON arguments must remain");
        require(arguments.contains(new com.google.gson.JsonPrimitive(BootstrapAgentInstaller.ownerArgument(owner))),
                "JSON owner guard must be installed");
        require(arguments.contains(new com.google.gson.JsonPrimitive(BootstrapAgentInstaller.agentArgument(agent))),
                "JSON Agent path must be installed");
        require(Files.isRegularFile(json.resolveSibling("version.json.lightspeed-backup")), "JSON backup must exist");
        require(!LaunchProfileInstaller.updateVersionJson(json, directory, owner, agent), "identical JSON update must be idempotent");
    }

    private static void checkPrism(Path directory, Path owner, Path agent) throws Exception {
        Path config = directory.resolve("instance.cfg");
        Files.writeString(config, "JvmArgs=-XX:+UseG1GC -javaagent:C:/old/lightspeed-bootstrap-agent-old.jar\nOverrideJavaArgs=true\n",
                StandardCharsets.UTF_8);
        require(LaunchProfileInstaller.updatePrism(config, directory, owner, agent), "Prism profile must change");
        String updated = Files.readString(config, StandardCharsets.UTF_8);
        require(updated.contains("-XX:+UseG1GC"), "Prism JVM arguments must be preserved");
        require(updated.contains("OverrideJavaArgs=true"), "Prism per-instance override must be enabled");
        require(!updated.contains("lightspeed-bootstrap-agent-old.jar"), "stale Prism bootstrap path must be removed");
        require(updated.contains(BootstrapAgentInstaller.agentArgument(agent)), "Prism Agent path must be installed");
        require(!LaunchProfileInstaller.updatePrism(config, directory, owner, agent), "identical Prism update must be idempotent");

        Path inherited = directory.resolve("inherited-instance.cfg");
        Files.writeString(inherited, "JvmArgs=\nOverrideJavaArgs=false\n", StandardCharsets.UTF_8);
        require(!LaunchProfileInstaller.updatePrism(inherited, directory, owner, agent),
                "Prism global JVM arguments must not be replaced by an instance override");
        require(Files.readString(inherited, StandardCharsets.UTF_8).contains("OverrideJavaArgs=false"),
                "Prism inherited configuration must remain unchanged");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
