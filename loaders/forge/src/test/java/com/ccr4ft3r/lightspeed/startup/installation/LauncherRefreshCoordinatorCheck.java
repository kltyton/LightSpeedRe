package com.ccr4ft3r.lightspeed.startup.installation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LauncherRefreshCoordinatorCheck {
    private LauncherRefreshCoordinatorCheck() {
    }

    public static void main(String[] args) {
        checkRestart(LaunchProfileInstaller.Result.PCL,
                process(10, "C:\\Games\\Plain Craft Launcher 2.exe"));
        checkRestart(LaunchProfileInstaller.Result.PCL_UNCHANGED,
                process(11, "C:\\Games\\PCL.exe"));
        checkRestart(LaunchProfileInstaller.Result.PRISM,
                process(12, "C:\\Games\\PrismLauncher.exe", "--show"));
        checkRestart(LaunchProfileInstaller.Result.PRISM_UNCHANGED,
                process(13, "C:\\Games\\MultiMC.exe"));
        checkRestart(LaunchProfileInstaller.Result.HMCL,
                process(14, "C:\\Java\\bin\\javaw.exe", "-jar", "C:\\Games\\HMCL.jar"));
        checkRestart(LaunchProfileInstaller.Result.OFFICIAL,
                process(15, "C:\\Games\\MinecraftLauncher.exe"));
        checkWrappedLauncher();
        checkUnknownParentUntouched();
        checkMissingLauncherNeedsNoRestart();
        checkGracefulCloseFailureUsesExactForceFallback();
        checkGracefulCloseExceptionUsesExactForceFallback();
        checkForceFailureDoesNotRelaunch();
        checkPremainSkipsRestart();
        checkAmbiguousVersionJsonStaysFailOpen();
        checkDiscardedOutputKeepsValidInputPipe();
        System.out.println("LAUNCHER_REFRESH_OK");
    }

    private static void checkRestart(LaunchProfileInstaller.Result result,
                                     LauncherProcessControl.LauncherProcess launcher) {
        FakeProcessAccess access = new FakeProcessAccess(List.of(launcher), true);
        LauncherRefreshCoordinator.Status status =
                LauncherRefreshCoordinator.refresh(result, false, access);
        require(status == LauncherRefreshCoordinator.Status.RESTARTED,
                result + " launcher was not restarted: " + status);
        require(access.closed.equals(List.of(launcher.pid())),
                result + " did not close the exact launcher PID");
        require(access.relaunched.equals(List.of(launcher)),
                result + " did not preserve launcher command and arguments");
        require(status.readyForNextLaunch(), result + " restart must make the next launch ready");
    }

    private static void checkWrappedLauncher() {
        LauncherProcessControl.LauncherProcess wrapper =
                process(20, "C:\\Windows\\System32\\cmd.exe", "/c", "launcher.cmd");
        LauncherProcessControl.LauncherProcess pcl =
                process(21, "C:\\Games\\Plain Craft Launcher 2.exe");
        FakeProcessAccess access = new FakeProcessAccess(List.of(wrapper, pcl), true);
        require(LauncherRefreshCoordinator.refresh(LaunchProfileInstaller.Result.PCL, false, access)
                        == LauncherRefreshCoordinator.Status.RESTARTED,
                "launcher behind a wrapper was not found");
        require(access.closed.equals(List.of(21L)), "wrapper was closed instead of PCL");
    }

    private static void checkUnknownParentUntouched() {
        FakeProcessAccess access = new FakeProcessAccess(
                List.of(process(30, "C:\\Windows\\explorer.exe")), true);
        LauncherRefreshCoordinator.Status status =
                LauncherRefreshCoordinator.refresh(LaunchProfileInstaller.Result.PCL, false, access);
        require(status == LauncherRefreshCoordinator.Status.UNSUPPORTED,
                "unknown parent must require manual restart");
        require(access.closed.isEmpty() && access.relaunched.isEmpty(),
                "unknown parent was modified");
    }

    private static void checkMissingLauncherNeedsNoRestart() {
        FakeProcessAccess access = new FakeProcessAccess(List.of(), true);
        LauncherRefreshCoordinator.Status status =
                LauncherRefreshCoordinator.refresh(LaunchProfileInstaller.Result.PCL, false, access);
        require(status == LauncherRefreshCoordinator.Status.NO_RUNNING_LAUNCHER,
                "an already-exited launcher must not be treated as a failure");
        require(status.readyForNextLaunch(), "a newly opened launcher will read persisted arguments");
    }

    private static void checkGracefulCloseFailureUsesExactForceFallback() {
        FakeProcessAccess access = new FakeProcessAccess(
                List.of(process(40, "C:\\Games\\Plain Craft Launcher 2.exe")), false, true);
        LauncherRefreshCoordinator.Status status =
                LauncherRefreshCoordinator.refresh(LaunchProfileInstaller.Result.PCL, false, access);
        require(status == LauncherRefreshCoordinator.Status.RESTARTED,
                "authorized force fallback did not restart the launcher");
        require(access.forced.equals(List.of(40L)), "force fallback touched the wrong PID");
        require(access.relaunched.equals(List.of(process(40, "C:\\Games\\Plain Craft Launcher 2.exe"))),
                "force fallback did not preserve the launcher command");
    }

    private static void checkForceFailureDoesNotRelaunch() {
        FakeProcessAccess access = new FakeProcessAccess(
                List.of(process(41, "C:\\Games\\Plain Craft Launcher 2.exe")), false, false);
        LauncherRefreshCoordinator.Status status =
                LauncherRefreshCoordinator.refresh(LaunchProfileInstaller.Result.PCL, false, access);
        require(status == LauncherRefreshCoordinator.Status.FAILED,
                "failed force close must remain visible");
        require(access.relaunched.isEmpty(), "launcher was duplicated after force-close failure");
    }

    private static void checkGracefulCloseExceptionUsesExactForceFallback() {
        FakeProcessAccess access = new FakeProcessAccess(
                List.of(process(42, "C:\\Games\\Plain Craft Launcher 2.exe")), false, true, true);
        LauncherRefreshCoordinator.Status status =
                LauncherRefreshCoordinator.refresh(LaunchProfileInstaller.Result.PCL, false, access);
        require(status == LauncherRefreshCoordinator.Status.RESTARTED,
                "graceful-close exception bypassed the authorized force fallback");
        require(access.forced.equals(List.of(42L)), "exception fallback touched the wrong PID");
    }

    private static void checkPremainSkipsRestart() {
        FakeProcessAccess access = new FakeProcessAccess(
                List.of(process(50, "C:\\Games\\Plain Craft Launcher 2.exe")), true);
        LauncherRefreshCoordinator.Status status =
                LauncherRefreshCoordinator.refresh(LaunchProfileInstaller.Result.PCL, true, access);
        require(status == LauncherRefreshCoordinator.Status.NOT_REQUIRED,
                "active premain must not restart the launcher");
        require(access.closed.isEmpty(), "premain launch touched its launcher");
    }

    private static void checkAmbiguousVersionJsonStaysFailOpen() {
        FakeProcessAccess access = new FakeProcessAccess(
                List.of(process(60, "C:\\Games\\Plain Craft Launcher 2.exe")), true);
        LauncherRefreshCoordinator.Status status =
                LauncherRefreshCoordinator.refresh(LaunchProfileInstaller.Result.VERSION_JSON, false, access);
        require(status == LauncherRefreshCoordinator.Status.UNSUPPORTED,
                "generic version JSON must not identify a launcher process");
        require(access.closed.isEmpty(), "ambiguous version JSON killed a launcher");
    }

    private static void checkDiscardedOutputKeepsValidInputPipe() {
        ProcessBuilder builder = LauncherProcessControl.discardOutput(new ProcessBuilder("java"));
        require(builder.redirectInput() == ProcessBuilder.Redirect.PIPE,
                "launcher process builder installed an invalid stdin redirect");
        require(builder.redirectOutput() == ProcessBuilder.Redirect.DISCARD,
                "launcher stdout must remain detached");
        require(builder.redirectError() == ProcessBuilder.Redirect.DISCARD,
                "launcher stderr must remain detached");
    }

    private static LauncherProcessControl.LauncherProcess process(long pid, String command,
                                                                   String... arguments) {
        return new LauncherProcessControl.LauncherProcess(
                pid, Path.of(command), List.of(arguments));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeProcessAccess implements LauncherProcessControl.ProcessAccess {
        private final List<LauncherProcessControl.LauncherProcess> ancestors;
        private final boolean closeResult;
        private final boolean forceResult;
        private final boolean closeThrows;
        private final List<Long> closed = new ArrayList<>();
        private final List<Long> forced = new ArrayList<>();
        private final List<LauncherProcessControl.LauncherProcess> relaunched = new ArrayList<>();

        private FakeProcessAccess(List<LauncherProcessControl.LauncherProcess> ancestors,
                                  boolean closeResult) {
            this(ancestors, closeResult, false);
        }

        private FakeProcessAccess(List<LauncherProcessControl.LauncherProcess> ancestors,
                                  boolean closeResult, boolean forceResult) {
            this(ancestors, closeResult, forceResult, false);
        }

        private FakeProcessAccess(List<LauncherProcessControl.LauncherProcess> ancestors,
                                  boolean closeResult, boolean forceResult, boolean closeThrows) {
            this.ancestors = ancestors;
            this.closeResult = closeResult;
            this.forceResult = forceResult;
            this.closeThrows = closeThrows;
        }

        @Override
        public List<LauncherProcessControl.LauncherProcess> ancestors() {
            return ancestors;
        }

        @Override
        public boolean close(LauncherProcessControl.LauncherProcess launcher) throws Exception {
            closed.add(launcher.pid());
            if (closeThrows) {
                throw new IllegalStateException("simulated close failure");
            }
            return closeResult;
        }

        @Override
        public boolean forceClose(LauncherProcessControl.LauncherProcess launcher) {
            forced.add(launcher.pid());
            return forceResult;
        }

        @Override
        public void relaunch(LauncherProcessControl.LauncherProcess launcher) {
            relaunched.add(launcher);
        }
    }
}
