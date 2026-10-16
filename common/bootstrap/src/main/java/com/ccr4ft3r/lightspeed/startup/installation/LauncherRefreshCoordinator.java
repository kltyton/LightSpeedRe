package com.ccr4ft3r.lightspeed.startup.installation;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import com.ccr4ft3r.lightspeed.startup.installation.LauncherProcessControl.LauncherProcess;
import com.ccr4ft3r.lightspeed.startup.installation.LauncherProcessControl.ProcessAccess;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class LauncherRefreshCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Status status = Status.NOT_ATTEMPTED;

    private LauncherRefreshCoordinator() {
    }

    static void refreshAfterInstall(LaunchProfileInstaller.Result result) {
        boolean premainActive = Boolean.getBoolean(BootstrapAgentInstaller.ACTIVE_PROPERTY)
                && !Boolean.getBoolean(BootstrapAgentInstaller.DYNAMIC_PROPERTY);
        if (premainActive) {
            status = Status.NOT_REQUIRED;
            return;
        }
        ProcessAccess access = LauncherProcessControl.system();
        List<LauncherProcess> ancestors = access.ancestors();
        Optional<LauncherProcess> launcher = matchingLauncher(result, ancestors);
        if (result.launcherFamily() == null) {
            status = Status.UNSUPPORTED;
            return;
        }
        if (ancestors.isEmpty()) {
            status = Status.NO_RUNNING_LAUNCHER;
            return;
        }
        if (launcher.isEmpty()) {
            status = Status.UNSUPPORTED;
            LOGGER.warn("Lightspeed updated {}, but could not identify the running launcher process. You may reopen the launcher before the next game launch",
                    result.description());
            return;
        }

        status = Status.SCHEDULED;
        Thread refreshThread = new Thread(() -> {
            Status completed = restart(launcher.get(), access);
            status = completed;
            if (completed == Status.RESTARTED) {
                LOGGER.info("Lightspeed refreshed the {} launcher after updating premain JVM arguments",
                        result.launcherFamily());
            } else {
                LOGGER.warn("Lightspeed was unable to refresh the {} launcher automatically. You may reopen it before the next game launch",
                        result.launcherFamily());
            }
        }, "Lightspeed-Launcher-Refresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    static Status refresh(LaunchProfileInstaller.Result result, boolean premainActive, ProcessAccess access) {
        if (premainActive) {
            return Status.NOT_REQUIRED;
        }
        if (result.launcherFamily() == null) {
            return Status.UNSUPPORTED;
        }
        List<LauncherProcess> ancestors = access.ancestors();
        if (ancestors.isEmpty()) {
            return Status.NO_RUNNING_LAUNCHER;
        }
        Optional<LauncherProcess> launcher = matchingLauncher(result, ancestors);
        if (launcher.isEmpty()) {
            return Status.UNSUPPORTED;
        }
        return restart(launcher.get(), access);
    }

    private static Status restart(LauncherProcess launcher, ProcessAccess access) {
        boolean gracefullyClosed;
        try {
            gracefullyClosed = access.close(launcher);
        } catch (Exception exception) {
            gracefullyClosed = false;
        }
        if (!gracefullyClosed) {
            LOGGER.warn("Lightspeed launcher process {} did not provide a graceful close surface; trying the approved exact-process restart fallback",
                    launcher.pid());
            try {
                if (!access.forceClose(launcher)) {
                    return Status.FAILED;
                }
            } catch (Exception exception) {
                return Status.FAILED;
            }
        }
        try {
            access.relaunch(launcher);
            return Status.RESTARTED;
        } catch (Exception exception) {
            return Status.FAILED;
        }
    }

    static boolean readyForNextLaunch() {
        return status.readyForNextLaunch();
    }

    static Status currentStatus() {
        return status;
    }

    private static Optional<LauncherProcess> matchingLauncher(
            LaunchProfileInstaller.Result result, List<LauncherProcess> ancestors) {
        if (result.launcherFamily() == null) {
            return Optional.empty();
        }
        return ancestors.stream()
                .filter(process -> matches(result.launcherFamily(), process))
                .findFirst();
    }

    private static boolean matches(LaunchProfileInstaller.LauncherFamily family, LauncherProcess process) {
        String executable = process.command().getFileName().toString().toLowerCase(Locale.ROOT);
        return switch (family) {
            case PCL -> executable.equals("pcl.exe") || executable.equals("pcl2.exe")
                    || executable.contains("plain craft launcher");
            case PRISM -> executable.equals("prismlauncher.exe") || executable.equals("prismlauncher")
                    || executable.equals("multimc.exe") || executable.equals("multimc");
            case HMCL -> executable.contains("hmcl") || isHmclJavaCommand(executable, process.arguments());
            case OFFICIAL -> executable.equals("minecraftlauncher.exe")
                    || executable.equals("minecraftlauncher")
                    || executable.contains("minecraft launcher");
        };
    }

    private static boolean isHmclJavaCommand(String executable, List<String> arguments) {
        if (!executable.equals("java.exe") && !executable.equals("javaw.exe")
                && !executable.equals("java") && !executable.equals("javaw")) {
            return false;
        }
        for (String argument : arguments) {
            String normalized = argument.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (normalized.endsWith("/hmcl.jar")
                    || normalized.equals("org.jackhuang.hmcl.launcher")) {
                return true;
            }
        }
        return false;
    }

    enum Status {
        NOT_ATTEMPTED(false),
        SCHEDULED(false),
        NOT_REQUIRED(true),
        NO_RUNNING_LAUNCHER(true),
        RESTARTED(true),
        UNSUPPORTED(false),
        FAILED(false);

        private final boolean readyForNextLaunch;

        Status(boolean readyForNextLaunch) {
            this.readyForNextLaunch = readyForNextLaunch;
        }

        boolean readyForNextLaunch() {
            return readyForNextLaunch;
        }
    }

}
