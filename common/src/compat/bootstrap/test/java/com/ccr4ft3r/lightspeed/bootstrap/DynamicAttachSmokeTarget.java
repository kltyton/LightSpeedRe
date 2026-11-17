package com.ccr4ft3r.lightspeed.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntBiFunction;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

public final class DynamicAttachSmokeTarget {
    private DynamicAttachSmokeTarget() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 4 && arguments[0].equals("--native-agent-install")) {
            compareAgentInstall(Path.of(arguments[1]), Boolean.parseBoolean(arguments[2]), Path.of(arguments[3]));
            return;
        }
        Path agent = Path.of(required("lightspeed.agent.jar")).toAbsolutePath().normalize();
        Path javaExecutable = Path.of(required("lightspeed.java21")).toAbsolutePath().normalize();
        Process helper = new ProcessBuilder(List.of(
                javaExecutable.toString(), "--add-modules", "jdk.attach", "-cp", agent.toString(),
                "com.ccr4ft3r.lightspeed.bootstrap.AgentAttachHelper",
                Long.toString(ProcessHandle.current().pid()), agent.toString()))
                .redirectErrorStream(true).start();
        if (!helper.waitFor(30, TimeUnit.SECONDS)) {
            helper.destroyForcibly();
            throw new AssertionError("dynamic attach helper timed out");
        }
        String output = new String(helper.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (helper.exitValue() != 0 || !output.contains("LIGHTSPEED_DYNAMIC_ATTACH_OK")) {
            throw new AssertionError("dynamic attach helper failed: " + output);
        }
        if (!Boolean.getBoolean("lightspeed.bootstrapAgent.active")
                || !Boolean.getBoolean("lightspeed.bootstrapAgent.dynamic")) {
            throw new AssertionError("agentmain did not publish dynamic Agent state");
        }
        System.out.println("LIGHTSPEED_DYNAMIC_ATTACH_SMOKE_OK");
    }

    private static void compareAgentInstall(Path directory, boolean nativeExpected, Path recordingPath) throws Exception {
        Files.createDirectories(directory);
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Class.forName("net.minecraftforge.fml.loading.FMLPaths", true, loader)
                .getMethod("loadAbsolutePaths", Path.class).invoke(null, directory);
        Class.forName("com.ccr4ft3r.lightspeed.startup.bootstrap.EarlyBootstrap", true, loader)
                .getMethod("start", Path.class, Path.class)
                .invoke(null, directory, directory.resolve("config/lightspeed-common.toml"));
        Class.forName("cpw.mods.cl.ModuleClassLoader", false, loader);
        Class.forName("net.minecraftforge.accesstransformer.parser.AccessTransformerList", false, loader);
        Class<?> installer = Class.forName("com.lightload.bootstrap.RuntimeAgentBootstrap", true, loader);
        long elapsed;
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ProcessStart");
            recording.start();
            long start = System.nanoTime();
            installer.getMethod("installEarly").invoke(null);
            elapsed = System.nanoTime() - start;
            recording.stop();
            System.out.println("processRecordingBytes=" + recording.getSize());
            System.out.println("processRecordingToDisk=" + recording.isToDisk());
            recording.dump(recordingPath);
            System.out.println("processDumpBytes=" + Files.size(recordingPath));
        }
        if (!Boolean.getBoolean("lightspeed.bootstrapAgent.active")
                || !Boolean.getBoolean("lightload.runtimeAgent.active")
                || !Boolean.getBoolean("lightload.agent.active")) {
            throw new AssertionError("Both original agent entries must activate: "
                    + System.getProperty("lightload.runtimeAgent.status") + ": "
                    + System.getProperty("lightload.runtimeAgent.detail"));
        }
        String status = System.getProperty("lightload.runtimeAgent.status");
        installer.getMethod("installEarly").invoke(null);
        if (!java.util.Objects.equals(status, System.getProperty("lightload.runtimeAgent.status"))) {
            throw new AssertionError("Repeated installation changed the original agent state");
        }
        long helpers = 0;
        try (RecordingFile recording = new RecordingFile(recordingPath)) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                if (event.getEventType().getName().equals("jdk.ProcessStart")
                        && event.getString("command").contains("com.lightload.bootstrap.AgentAttacher")) helpers++;
            }
        }
        if (helpers != (nativeExpected ? 0 : 1)) {
            throw new AssertionError("Unexpected number of real external helpers: " + helpers);
        }
        System.out.println("installNanos=" + elapsed);
        System.out.println("externalHelpers=" + helpers);
        System.out.println("originalAgentStatus=" + status);
        System.out.println("bothAgentsActive=true");
        System.out.println("repeatedInstallStable=true");
        if (nativeExpected) checkInstallStatusBranches(installer);
        System.out.println("NATIVE_AGENT_INSTALL_SIMULATION_OK");
    }

    private static void checkInstallStatusBranches(Class<?> installer) throws Exception {
        Class<?> hooks = Class.forName("com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks", false, null);
        var publish = hooks.getMethod("registerNativeAgentLoader", ToIntBiFunction.class);
        var location = installer.getProtectionDomain().getCodeSource().getLocation();
        System.clearProperty("lightload.agent.active");
        System.clearProperty("lightload.runtimeAgent.active");
        try {
            for (int code : new int[]{100, 0}) {
                System.clearProperty("lightload.runtimeAgent.status");
                System.clearProperty("lightload.runtimeAgent.detail");
                ToIntBiFunction<Path, String> response = (path, options) -> code;
                publish.invoke(null, response);
                try (URLClassLoader isolated = new URLClassLoader(new java.net.URL[]{location}, ClassLoader.getPlatformClassLoader())) {
                    Class.forName(installer.getName(), true, isolated).getMethod("installEarly").invoke(null);
                }
                String expected = code == 0 ? "attach-inactive" : "attach-failed";
                if (!expected.equals(System.getProperty("lightload.runtimeAgent.status"))) {
                    throw new AssertionError("Original status tail did not preserve " + expected);
                }
                if (Boolean.getBoolean("lightload.runtimeAgent.active")) {
                    throw new AssertionError("A transport status was mistaken for agent activation");
                }
            }
            System.out.println("statusBranchesPassed=true");
            System.out.println("statusBranchTransportStubs=2");
        } finally {
            System.setProperty("lightload.agent.active", "true");
            System.setProperty("lightload.runtimeAgent.active", "true");
        }
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing system property " + key);
        }
        return value;
    }
}
