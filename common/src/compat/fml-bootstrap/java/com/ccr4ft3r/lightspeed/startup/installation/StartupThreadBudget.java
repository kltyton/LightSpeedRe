package com.ccr4ft3r.lightspeed.startup.installation;

import java.util.List;
import java.util.Locale;

final class StartupThreadBudget {
    private static final String MANAGED_COMPILER_COUNT = "-Dlightspeed.managedCICompilerCount=";

    private StartupThreadBudget() {
    }

    static List<String> arguments() {
        return arguments(
                System.getProperty("java.vm.name", ""),
                System.getProperty("java.vm.version", ""),
                Runtime.getRuntime().availableProcessors());
    }

    static List<String> arguments(String vmName, String vmVersion, int processors) {
        String normalizedName = vmName.toLowerCase(Locale.ROOT);
        String normalizedVersion = vmVersion.toLowerCase(Locale.ROOT);
        boolean hotspot = normalizedName.contains("hotspot") || normalizedName.contains("openjdk");
        boolean graal = normalizedName.contains("graal") || normalizedVersion.contains("jvmci");
        if (!hotspot || graal || processors < 8) {
            return List.of();
        }

        int workers = Math.max(2, Math.min(8, processors * 3 / 8));
        int compilerThreads = Math.max(2, Math.min(6, processors / 4));
        return List.of(
                "-Dlightspeed.workers=" + workers,
                MANAGED_COMPILER_COUNT + compilerThreads,
                "-XX:CICompilerCount=" + compilerThreads);
    }

    static Integer managedCompilerCount(String normalizedArgument) {
        String prefix = MANAGED_COMPILER_COUNT.toLowerCase(Locale.ROOT);
        if (!normalizedArgument.startsWith(prefix)) {
            return null;
        }
        try {
            int value = Integer.parseInt(normalizedArgument.substring(prefix.length()));
            return value >= 2 && value <= 64 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static boolean isManagedCompilerArgument(String normalizedArgument, int compilerCount) {
        return normalizedArgument.equals("-xx:cicompilercount=" + compilerCount);
    }
}
