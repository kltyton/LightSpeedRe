package com.ccr4ft3r.lightspeed.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class DynamicAttachSmokeTarget {
    private DynamicAttachSmokeTarget() {
    }

    public static void main(String[] arguments) throws Exception {
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

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing system property " + key);
        }
        return value;
    }
}
