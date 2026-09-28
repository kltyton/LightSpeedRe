package com.ccr4ft3r.lightspeed.startup.installation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class LauncherProcessControl {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ANCESTORS = 8;

    private LauncherProcessControl() {
    }

    static ProcessAccess system() {
        return new SystemProcessAccess();
    }

    record LauncherProcess(long pid, Path command, List<String> arguments) {
        LauncherProcess {
            command = command.toAbsolutePath().normalize();
            arguments = List.copyOf(arguments);
        }
    }

    interface ProcessAccess {
        List<LauncherProcess> ancestors();

        boolean close(LauncherProcess launcher) throws Exception;

        boolean forceClose(LauncherProcess launcher) throws Exception;

        void relaunch(LauncherProcess launcher) throws Exception;
    }

    private static final class SystemProcessAccess implements ProcessAccess {
        @Override
        public List<LauncherProcess> ancestors() {
            List<LauncherProcess> result = new ArrayList<>();
            Optional<ProcessHandle> current = ProcessHandle.current().parent();
            while (current.isPresent() && result.size() < MAX_ANCESTORS) {
                ProcessHandle process = current.get();
                ProcessHandle.Info info = process.info();
                info.command().ifPresent(command -> {
                    try {
                        result.add(new LauncherProcess(process.pid(), Path.of(command),
                                List.of(info.arguments().orElseGet(() -> new String[0]))));
                    } catch (RuntimeException ignored) {
                        // An inaccessible or malformed command is not a restart candidate.
                    }
                });
                current = process.parent();
            }
            return result;
        }

        @Override
        public boolean close(LauncherProcess launcher) throws Exception {
            if (isWindows()) {
                return closeWindowsLauncher(launcher.pid());
            }
            Optional<ProcessHandle> process = ProcessHandle.of(launcher.pid());
            if (process.isEmpty() || !process.get().destroy()) {
                return false;
            }
            process.get().onExit().get(CLOSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return true;
        }

        @Override
        public void relaunch(LauncherProcess launcher) throws Exception {
            List<String> command = new ArrayList<>(launcher.arguments().size() + 1);
            command.add(launcher.command().toString());
            command.addAll(launcher.arguments());
            ProcessBuilder builder = discardOutput(new ProcessBuilder(command));
            Path parent = launcher.command().getParent();
            if (parent != null && Files.isDirectory(parent)) {
                builder.directory(parent.toFile());
            }
            builder.start();
        }

        @Override
        public boolean forceClose(LauncherProcess launcher) throws Exception {
            Optional<ProcessHandle> process = ProcessHandle.of(launcher.pid());
            if (process.isEmpty()) {
                return true;
            }
            if (!process.get().destroyForcibly()) {
                return false;
            }
            process.get().onExit().get(CLOSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return !process.get().isAlive();
        }

        private static boolean closeWindowsLauncher(long pid) throws Exception {
            String script = "$ErrorActionPreference='Stop';"
                    + "Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;"
                    + "public static class LightspeedWindows{public delegate bool Callback(IntPtr h,IntPtr l);"
                    + "[DllImport(\"user32.dll\")]public static extern bool EnumWindows(Callback c,IntPtr l);"
                    + "[DllImport(\"user32.dll\")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);"
                    + "[DllImport(\"user32.dll\")]public static extern bool PostMessage(IntPtr h,uint m,IntPtr w,IntPtr l);"
                    + "public static int Close(uint target){int sent=0;EnumWindows((h,l)=>{uint p;GetWindowThreadProcessId(h,out p);"
                    + "if(p==target&&PostMessage(h,0x10,IntPtr.Zero,IntPtr.Zero))sent++;return true;},IntPtr.Zero);return sent;}}';"
                    + "$p=Get-Process -Id ([long]$env:LIGHTSPEED_LAUNCHER_PID) -ErrorAction Stop;"
                    + "$sent=[LightspeedWindows]::Close([uint32]$p.Id);if($sent-lt1){exit 2};"
                    + "if(-not $p.WaitForExit(15000)){exit 3};exit 0";
            ProcessBuilder builder = discardOutput(new ProcessBuilder(
                    windowsPowerShell().toString(), "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-Command", script));
            builder.environment().put("LIGHTSPEED_LAUNCHER_PID", Long.toString(pid));
            Process helper = builder.start();
            if (!helper.waitFor(CLOSE_TIMEOUT.plusSeconds(10).toSeconds(), TimeUnit.SECONDS)) {
                helper.destroyForcibly();
                return false;
            }
            return helper.exitValue() == 0;
        }

        private static Path windowsPowerShell() {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null && !systemRoot.isBlank()) {
                Path executable = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0",
                        "powershell.exe");
                if (Files.isRegularFile(executable)) {
                    return executable;
                }
            }
            return Path.of("powershell.exe");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static ProcessBuilder discardOutput(ProcessBuilder builder) {
        return builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
    }
}
