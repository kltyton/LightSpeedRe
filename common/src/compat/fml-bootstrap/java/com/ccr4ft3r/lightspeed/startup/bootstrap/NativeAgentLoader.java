package com.ccr4ft3r.lightspeed.startup.bootstrap;

import com.ccr4ft3r.lightspeed.startup.bootstrap.path.WindowsPayloadPath;
import com.sun.jna.Function;
import com.sun.jna.FunctionMapper;
import com.sun.jna.JNIEnv;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Loads the instance payload through OpenJDK's executable-JAR instrumentation entry. */
final class NativeAgentLoader {
    private static final String ENTRY = "Java_sun_instrument_InstrumentationImpl_loadAgent0";
    private static NativeLibrary instrumentationLibrary;
    private static boolean executableEntryRegistered;
    private static Pointer virtualMachine;
    private static Function attachEntry;

    private NativeAgentLoader() { }

    static synchronized void load(Path payload) throws IOException {
        if (!executableEntryRegistered) {
            Native.register(Entry.class, instrumentationLibrary());
            executableEntryRegistered = true;
        }
        Path absolute = payload.toAbsolutePath().normalize();
        String key = "lightspeed.bootstrapAgent.loaderArchive";
        String previous = System.getProperty(key);
        System.setProperty(key, absolute.toString());
        try {
            if (Platform.isWindows() && absolute.toString().chars().anyMatch(character -> character > 0x7f)) {
                try (WindowsPayloadPath alias = WindowsPayloadPath.open(absolute)) {
                    Entry.loadAgent(JNIEnv.CURRENT, null, alias.path());
                    alias.retainForProcess();
                }
            } else {
                Entry.loadAgent(JNIEnv.CURRENT, null, absolute.toString());
            }
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    private static final class Entry {
        // Object preserves the Java String as jstring; JNA's String mapping would produce char*.
        private static native void loadAgent(JNIEnv environment, Class<?> ignored, Object jarPath);
    }

    private static NativeLibrary instrumentationLibrary() throws IOException {
        if (instrumentationLibrary == null) {
            Path library = Path.of(System.getProperty("java.home"))
                    .resolve(Platform.isWindows() ? "bin" : "lib")
                    .resolve(System.mapLibraryName("instrument"));
            if (!Files.isRegularFile(library)) {
                throw new IOException("The current Java runtime does not provide its instrumentation library: " + library);
            }
            // JPLIS owns native callbacks for the rest of this JVM's lifetime.
            instrumentationLibrary = NativeLibrary.getInstance(library.toString(), Map.of(
                    Library.OPTION_ALLOW_OBJECTS, true,
                    Library.OPTION_FUNCTION_MAPPER, (FunctionMapper) (ignored, method) -> ENTRY,
                    Library.OPTION_CALLING_CONVENTION,
                    Platform.isWindows() && Platform.isIntel() && !Platform.is64Bit()
                            ? Function.ALT_CONVENTION : Function.C_CONVENTION));
        }
        return instrumentationLibrary;
    }

    /** Uses the same JPLIS entry and manifest capabilities as a successful Attach API request. */
    static synchronized int loadAttached(Path payload, String options) throws IOException {
        if (attachEntry == null) {
            Path javaHome = Path.of(System.getProperty("java.home"));
            Path vmFile = javaHome.resolve(Platform.isWindows() ? "bin" : "lib")
                    .resolve("server").resolve(System.mapLibraryName("jvm"));
            if (!Files.isRegularFile(vmFile)) {
                throw new IOException("The current runtime has no shared JVM library: " + vmFile);
            }
            int convention = Platform.isWindows() && Platform.isIntel() && !Platform.is64Bit()
                    ? Function.ALT_CONVENTION : Function.C_CONVENTION;
            NativeLibrary vmLibrary = NativeLibrary.getInstance(vmFile.toString());
            PointerByReference vm = new PointerByReference();
            IntByReference count = new IntByReference();
            int status = vmLibrary.getFunction("JNI_GetCreatedJavaVMs", convention)
                    .invokeInt(new Object[]{vm, 1, count});
            if (status != 0 || count.getValue() != 1 || vm.getValue() == null) {
                throw new IOException("Cannot identify the current JVM: status=" + status
                        + ", count=" + count.getValue());
            }
            Function entry = instrumentationLibrary().getFunction("Agent_OnAttach", convention);
            virtualMachine = vm.getValue();
            attachEntry = entry;
        }
        Path absolute = payload.toAbsolutePath().normalize();
        if (Platform.isWindows() && absolute.toString().chars().anyMatch(character -> character > 0x7f)) {
            try (WindowsPayloadPath alias = WindowsPayloadPath.open(absolute)) {
                int status = attach(alias.path(), options);
                if (status == 0) alias.retainForProcess();
                return status;
            }
        }
        return attach(absolute.toString(), options);
    }

    private static int attach(String path, String options) {
        String arguments = options == null ? path : path + '=' + options;
        return attachEntry.invokeInt(new Object[]{virtualMachine, arguments, null});
    }
}
