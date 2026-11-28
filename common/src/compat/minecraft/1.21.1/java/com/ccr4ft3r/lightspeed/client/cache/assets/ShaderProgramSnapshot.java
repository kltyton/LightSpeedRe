package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.ccr4ft3r.lightspeed.cache.assets.SnapshotFileStore;
import com.ccr4ft3r.lightspeed.cache.assets.SnapshotMode;
import com.ccr4ft3r.lightspeed.platform.LoaderEnvironment;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.SharedConstants;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShaderProgramSnapshot {
    private static final int MAX_BINARY_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ERROR_DRAIN = 32;
    private static final String RAW_SCHEMA = "lightspeed-shader-program-raw-v2";
    private static final boolean ENABLED = SnapshotMode.shaderProgramsEnabled(
            System.getProperty("lightspeed.shaderProgramSnapshot"));
    private static final SnapshotFileStore STORE = ENABLED ? new SnapshotFileStore(
            assetDirectory().resolve("shader-programs-v3.bin"), 0x4c535348, 3,
            16_384, MAX_BINARY_BYTES + Integer.BYTES, 512L * 1024L * 1024L) : null;
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();
    private static volatile DriverProfile cachedDriverProfile;

    private ShaderProgramSnapshot() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean supported() {
        return driverProfile() != null;
    }

    public static Program createDummyProgram(Program.Type type, String name) {
        return new DummyProgram(type, name);
    }

    public static boolean restoreRaw(
            Shader shader,
            String vertexDigest,
            String fragmentDigest,
            VertexFormat vertexFormat) {
        DriverProfile profile = driverProfile();
        if (profile == null || vertexDigest == null || fragmentDigest == null) {
            return false;
        }
        String key = contentKey(profile, vertexDigest, fragmentDigest, vertexFormat);
        byte[] snapshot = STORE.get(key);
        if (snapshot == null || snapshot.length <= Integer.BYTES) {
            return false;
        }
        ByteBuffer data = MemoryUtil.memAlloc(snapshot.length - Integer.BYTES);
        try {
            ByteBuffer encoded = ByteBuffer.wrap(snapshot);
            int format = encoded.getInt();
            data.put(encoded).flip();
            GL41.glProgramBinary(shader.getId(), format, data);
            boolean linked = GL20.glGetProgrami(shader.getId(), GL20.GL_LINK_STATUS) != 0;
            if (!linked) {
                drainGlErrors();
            }
            return linked;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    public static void prepareCapture(Shader shader) {
        if (supported()) {
            GL41.glProgramParameteri(shader.getId(), GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11.GL_TRUE);
        }
    }

    public static void captureRaw(
            Shader shader,
            String vertexDigest,
            String fragmentDigest,
            VertexFormat vertexFormat) {
        DriverProfile profile = driverProfile();
        if (profile == null || vertexDigest == null || fragmentDigest == null) {
            return;
        }
        int programId = shader.getId();
        if (!isSimpleTwoStageProgram(programId, profile.capabilities())) {
            return;
        }
        int capacity = GL20.glGetProgrami(programId, GL41.GL_PROGRAM_BINARY_LENGTH);
        if (capacity <= 0 || capacity > MAX_BINARY_BYTES) {
            return;
        }
        ByteBuffer binary = MemoryUtil.memAlloc(capacity);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer length = stack.mallocInt(1);
            IntBuffer format = stack.mallocInt(1);
            GL41.glGetProgramBinary(programId, length, format, binary);
            int actual = length.get(0);
            if (actual <= 0 || actual > capacity) {
                drainGlErrors();
                return;
            }
            byte[] encoded = new byte[Integer.BYTES + actual];
            ByteBuffer output = ByteBuffer.wrap(encoded);
            output.putInt(format.get(0));
            binary.limit(actual).position(0);
            output.put(binary);
            if (STORE.put(contentKey(profile, vertexDigest, fragmentDigest, vertexFormat), encoded)) {
                registerShutdownPersistHook();
            }
        } finally {
            MemoryUtil.memFree(binary);
        }
    }

    private static boolean isSimpleTwoStageProgram(int programId, GLCapabilities capabilities) {
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == 0) {
            return false;
        }
        if (GL20.glGetProgrami(programId, GL20.GL_ATTACHED_SHADERS) != 2) {
            return false;
        }
        if (capabilities.OpenGL30
                && GL20.glGetProgrami(programId, GL30.GL_TRANSFORM_FEEDBACK_VARYINGS) != 0) {
            return false;
        }
        return !(capabilities.OpenGL41
                && GL20.glGetProgrami(programId, GL41.GL_PROGRAM_SEPARABLE) != 0);
    }

    private static void registerShutdownPersistHook() {
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(ShaderProgramSnapshot::persist, "Lightspeed-ShaderProgramSnapshot-Persist"));
        } catch (IllegalStateException | SecurityException ignored) {
            // Ignore if JVM shutdown is already in progress or hooks are disallowed.
        }
    }

    private static void drainGlErrors() {
        for (int index = 0; index < MAX_ERROR_DRAIN && GL11.glGetError() != GL11.GL_NO_ERROR; index++) {
            // Drain driver binary rejection errors before falling back to full compilation.
        }
    }

    public static void persist() {
        if (ENABLED) {
            STORE.persist();
        }
    }

    public static long hits() {
        return ENABLED ? STORE.hits() : 0;
    }

    public static long misses() {
        return ENABLED ? STORE.misses() : 0;
    }

    public static long failures() {
        return ENABLED ? STORE.failures() : 0;
    }

    private static DriverProfile driverProfile() {
        if (!ENABLED) {
            return null;
        }
        GLCapabilities capabilities;
        try {
            capabilities = GL.getCapabilities();
        } catch (IllegalStateException exception) {
            return null;
        }
        if (capabilities == null || (!capabilities.OpenGL41 && !capabilities.GL_ARB_get_program_binary)) {
            return null;
        }
        DriverProfile current = cachedDriverProfile;
        if (current != null && current.capabilities() == capabilities) {
            return current;
        }
        int[] formats = binaryFormats();
        if (formats.length == 0) {
            return null;
        }
        DriverProfile created = new DriverProfile(
                capabilities,
                String.valueOf(GL11.glGetString(GL11.GL_VENDOR)),
                String.valueOf(GL11.glGetString(GL11.GL_RENDERER)),
                String.valueOf(GL11.glGetString(GL11.GL_VERSION)),
                String.valueOf(GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION)),
                formats);
        cachedDriverProfile = created;
        return created;
    }

    private static String contentKey(
            DriverProfile profile,
            String vertexDigest,
            String fragmentDigest,
            VertexFormat vertexFormat) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, RAW_SCHEMA);
            update(digest, profile.vendor());
            update(digest, profile.renderer());
            update(digest, profile.glVersion());
            update(digest, profile.glslVersion());
            update(digest, vertexDigest);
            update(digest, fragmentDigest);
            for (int format : profile.binaryFormats()) {
                update(digest, Integer.toUnsignedString(format));
            }
            var elements = vertexFormat.getElements();
            var attributes = vertexFormat.getElementAttributeNames();
            for (int index = 0; index < elements.size(); index++) {
                VertexFormatElement element = elements.get(index);
                update(digest, attributes.get(index));
                update(digest, element.type().name());
                update(digest, element.usage().name());
                update(digest, Integer.toString(element.index()));
                update(digest, Integer.toString(element.count()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int[] binaryFormats() {
        int count = Math.max(0, GL11.glGetInteger(GL41.GL_NUM_PROGRAM_BINARY_FORMATS));
        if (count == 0 || count > 256) {
            return new int[0];
        }
        IntBuffer formats = BufferUtils.createIntBuffer(count);
        GL11.glGetIntegerv(GL41.GL_PROGRAM_BINARY_FORMATS, formats);
        int[] result = new int[count];
        formats.get(result);
        Arrays.sort(result);
        return result;
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static Path assetDirectory() {
        return LoaderEnvironment.gameDirectory().resolve("lightspeed-cache")
                .resolve(SharedConstants.getCurrentVersion().getId()).resolve("client-assets");
    }

    private record DriverProfile(
            GLCapabilities capabilities,
            String vendor,
            String renderer,
            String glVersion,
            String glslVersion,
            int[] binaryFormats) {
    }

    private static final class DummyProgram extends Program {
        private DummyProgram(Program.Type type, String name) {
            super(type, 0, name);
        }

        @Override
        public void attachToShader(Shader shader) {
        }

        @Override
        public void close() {
        }
    }
}
