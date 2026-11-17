package com.ccr4ft3r.lightspeed.startup.bootstrap.path;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Gives the JDK's ANSI manifest reader an ASCII name for a Unicode payload directory. */
public final class WindowsPayloadPath implements AutoCloseable {
    private static final int OBJECT_NAME_COLLISION = 0xc0000035;
    private static final Map<String, Object> OPTIONS = Map.of(
            Library.OPTION_CALLING_CONVENTION, Platform.isIntel() && !Platform.is64Bit()
                    ? Function.ALT_CONVENTION : Function.C_CONVENTION);
    private static final Kernel32 KERNEL = Native.load("kernel32", Kernel32.class, OPTIONS);
    private static final NtDll NT = Native.load("ntdll", NtDll.class, OPTIONS);

    private final Pointer handle;
    private final String path;
    private boolean retained;

    private WindowsPayloadPath(Path payload) throws IOException {
        String directory = payload.getParent().toString();
        UnicodeString target = new UnicodeString(directory.startsWith("\\\\")
                ? "\\??\\UNC\\" + directory.substring(2) : "\\??\\" + directory);
        int drives = KERNEL.GetLogicalDrives();
        if (drives == 0) {
            throw new IOException("Cannot enumerate DOS drive names: Windows error " + Native.getLastError());
        }
        for (char letter = 'Z'; letter >= 'A'; letter--) {
            if ((drives & (1 << (letter - 'A'))) != 0) {
                continue;
            }
            ObjectAttributes attributes = new ObjectAttributes(new UnicodeString("\\??\\" + letter + ":"));
            PointerByReference result = new PointerByReference();
            int status = NT.NtCreateSymbolicLinkObject(result, 1, attributes, target);
            if (status == OBJECT_NAME_COLLISION) {
                continue;
            }
            if (status < 0) {
                throw new IOException("Cannot name the bootstrap payload directory: NTSTATUS 0x"
                        + Integer.toHexString(status));
            }
            handle = result.getValue();
            path = letter + ":\\" + payload.getFileName();
            return;
        }
        throw new IOException("No unused DOS drive name is available for the bootstrap payload");
    }

    public static WindowsPayloadPath open(Path payload) throws IOException {
        return new WindowsPayloadPath(payload);
    }

    public String path() {
        return path;
    }

    public void retainForProcess() {
        // The non-permanent object disappears when Windows closes this process's handles, including after a crash.
        retained = true;
    }

    @Override
    public void close() throws IOException {
        if (!retained) {
            release();
        }
    }

    private void release() throws IOException {
        int status = NT.NtClose(handle);
        if (status < 0) {
            throw new IOException("Cannot release the bootstrap payload directory name: NTSTATUS 0x"
                    + Integer.toHexString(status));
        }
    }

    @Structure.FieldOrder({"length", "maximumLength", "buffer"})
    public static final class UnicodeString extends Structure implements Structure.ByReference {
        public short length;
        public short maximumLength;
        public Pointer buffer;
        private Memory contents;

        public UnicodeString() { }

        public UnicodeString(String text) throws IOException {
            int bytes = text.length() * Native.WCHAR_SIZE;
            if (bytes > 65532) {
                throw new IOException("The native bootstrap path exceeds UNICODE_STRING capacity");
            }
            contents = new Memory(bytes + Native.WCHAR_SIZE);
            contents.setWideString(0, text);
            length = (short) bytes;
            maximumLength = (short) (bytes + Native.WCHAR_SIZE);
            buffer = contents;
        }
    }

    @Structure.FieldOrder({"length", "rootDirectory", "objectName", "attributes", "securityDescriptor", "securityQualityOfService"})
    public static final class ObjectAttributes extends Structure {
        public int length;
        public Pointer rootDirectory;
        public UnicodeString objectName;
        public int attributes;
        public Pointer securityDescriptor;
        public Pointer securityQualityOfService;

        public ObjectAttributes() {
            length = size();
        }

        public ObjectAttributes(UnicodeString name) {
            this();
            objectName = name;
            attributes = 0x40;
        }
    }

    private interface Kernel32 extends Library {
        int GetLogicalDrives();
    }

    private interface NtDll extends Library {
        int NtCreateSymbolicLinkObject(PointerByReference handle, int access,
                ObjectAttributes attributes, UnicodeString target);

        int NtClose(Pointer handle);
    }
}
