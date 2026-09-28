package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.ccr4ft3r.lightspeed.cache.assets.SnapshotFileStore;
import com.ccr4ft3r.lightspeed.cache.assets.SnapshotMode;
import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.SharedConstants;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL41;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public final class ShaderProgramSnapshot {
    private static final int MAX_BINARY_BYTES = 64 * 1024 * 1024;
    private static final boolean ENABLED = SnapshotMode.shaderProgramsEnabled(
            System.getProperty("lightspeed.shaderProgramSnapshot"));
    private static final SnapshotFileStore STORE = ENABLED ? new SnapshotFileStore(
            assetDirectory().resolve("shader-programs-v2.bin"), 0x4c535348, 2,
            16_384, MAX_BINARY_BYTES + Integer.BYTES, 512L * 1024L * 1024L) : null;

    private ShaderProgramSnapshot() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean restore(Shader shader, VertexFormat vertexFormat, boolean bindAttributes) {
        if (!supported()) {
            return false;
        }
        String key = key(shader, vertexFormat, bindAttributes);
        if (key == null) {
            return false;
        }
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
            return GL20.glGetProgrami(shader.getId(), GL20.GL_LINK_STATUS) != 0;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    public static void prepareCapture(Shader shader) {
        if (supported()) {
            GL41.glProgramParameteri(shader.getId(), GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11.GL_TRUE);
        }
    }

    public static void capture(Shader shader, VertexFormat vertexFormat, boolean bindAttributes) {
        if (!supported() || GL20.glGetProgrami(shader.getId(), GL20.GL_LINK_STATUS) == 0) {
            return;
        }
        int capacity = GL20.glGetProgrami(shader.getId(), GL41.GL_PROGRAM_BINARY_LENGTH);
        if (capacity <= 0 || capacity > MAX_BINARY_BYTES) {
            return;
        }
        ByteBuffer binary = MemoryUtil.memAlloc(capacity);
        IntBuffer length = BufferUtils.createIntBuffer(1);
        IntBuffer format = BufferUtils.createIntBuffer(1);
        try {
            GL41.glGetProgramBinary(shader.getId(), length, format, binary);
            int actual = length.get(0);
            if (actual <= 0 || actual > capacity) {
                return;
            }
            byte[] encoded = new byte[Integer.BYTES + actual];
            ByteBuffer output = ByteBuffer.wrap(encoded);
            output.putInt(format.get(0));
            binary.limit(actual).position(0);
            output.put(binary);
            String key = key(shader, vertexFormat, bindAttributes);
            if (key != null) {
                STORE.put(key, encoded);
            }
        } finally {
            MemoryUtil.memFree(binary);
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

    private static boolean supported() {
        if (!ENABLED) {
            return false;
        }
        try {
            return GL.getCapabilities().OpenGL41 || GL.getCapabilities().GL_ARB_get_program_binary;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static String key(Shader shader, VertexFormat vertexFormat, boolean bindAttributes) {
        String vertexDigest = ShaderSourceIdentity.digest(Program.Type.VERTEX, shader.getVertexProgram());
        String fragmentDigest = ShaderSourceIdentity.digest(Program.Type.FRAGMENT, shader.getFragmentProgram());
        if (vertexDigest == null || fragmentDigest == null) {
            return null;
        }
        return contentKey(
                String.valueOf(GL11.glGetString(GL11.GL_VENDOR)),
                String.valueOf(GL11.glGetString(GL11.GL_RENDERER)),
                String.valueOf(GL11.glGetString(GL11.GL_VERSION)),
                String.valueOf(GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION)),
                binaryFormats(), vertexDigest, fragmentDigest, vertexFormat, bindAttributes);
    }

    static String contentKey(String vendor, String renderer, String glVersion, String glslVersion,
                             int[] binaryFormats, String vertexDigest, String fragmentDigest,
                             VertexFormat vertexFormat, boolean bindAttributes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "lightspeed-shader-program-v2");
            update(digest, vendor);
            update(digest, renderer);
            update(digest, glVersion);
            update(digest, glslVersion);
            update(digest, vertexDigest);
            update(digest, fragmentDigest);
            update(digest, Boolean.toString(bindAttributes));
            for (int format : binaryFormats) {
                update(digest, Integer.toUnsignedString(format));
            }
            for (Map.Entry<String, VertexFormatElement> entry : vertexFormat.getElementMapping().entrySet()) {
                VertexFormatElement element = entry.getValue();
                update(digest, entry.getKey());
                update(digest, element.getType().name());
                update(digest, element.getUsage().name());
                update(digest, Integer.toString(element.getIndex()));
                update(digest, Integer.toString(element.getCount()));
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
        java.util.Arrays.sort(result);
        return result;
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static Path assetDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("lightspeed-cache")
                .resolve(SharedConstants.getCurrentVersion().getId()).resolve("client-assets");
    }
}
