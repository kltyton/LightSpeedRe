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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShaderProgramSnapshot {
    private static final int MAX_BINARY_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ATTACHED_SHADERS = 32;
    private static final int MAX_TRACKED_PROGRAMS = 256;
    private static final int MAX_ERROR_DRAIN = 32;
    private static final String PROCESSED_SCHEMA = "lightspeed-shader-program-v3";
    private static final String RAW_SCHEMA = "lightspeed-shader-program-raw-v2";
    private static final boolean ENABLED = SnapshotMode.shaderProgramsEnabled(
            System.getProperty("lightspeed.shaderProgramSnapshot"));
    private static final SnapshotFileStore STORE = ENABLED ? new SnapshotFileStore(
            assetDirectory().resolve("shader-programs-v3.bin"), 0x4c535348, 3,
            16_384, MAX_BINARY_BYTES + Integer.BYTES, 512L * 1024L * 1024L) : null;
    private static final Map<Integer, List<AttributeBinding>> TRACKED_BINDINGS = new ConcurrentHashMap<>();
    private static final ThreadLocal<LinkContext> ACTIVE_LINK_CONTEXT = new ThreadLocal<>();
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

    public static void beginTrackingProgram(int programId) {
        if (!ENABLED || programId <= 0) {
            return;
        }
        if (TRACKED_BINDINGS.size() >= MAX_TRACKED_PROGRAMS) {
            TRACKED_BINDINGS.clear();
        }
        TRACKED_BINDINGS.put(programId, new ArrayList<>());
    }

    public static void recordBoundAttribute(int programId, int index, CharSequence name) {
        if (!ENABLED || programId <= 0 || name == null) {
            return;
        }
        List<AttributeBinding> bindings = TRACKED_BINDINGS.get(programId);
        if (bindings != null) {
            synchronized (bindings) {
                bindings.add(new AttributeBinding(index, name.toString()));
            }
        }
    }

    public static void forgetProgram(int programId) {
        if (programId > 0) {
            TRACKED_BINDINGS.remove(programId);
        }
    }

    public static boolean matchesDefaultAttributeBindings(
            int programId, VertexFormat vertexFormat, boolean bindAttributes) {
        if (!ENABLED || programId <= 0 || vertexFormat == null) {
            return false;
        }
        List<AttributeBinding> bindings = TRACKED_BINDINGS.get(programId);
        if (bindings == null) {
            return false;
        }
        synchronized (bindings) {
            if (!bindAttributes) {
                return bindings.isEmpty();
            }
            List<String> expected = vertexFormat.getElementAttributeNames();
            if (bindings.size() != expected.size()) {
                return false;
            }
            for (int index = 0; index < expected.size(); index++) {
                AttributeBinding actual = bindings.get(index);
                if (actual.index() != index || !actual.name().equals(expected.get(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    public static void withLinkContext(
            Shader shader,
            VertexFormat vertexFormat,
            boolean bindAttributes,
            Runnable linkAction) {
        if (!ENABLED || shader == null || vertexFormat == null) {
            linkAction.run();
            return;
        }
        int programId = shader.getId();
        LinkContext previous = ACTIVE_LINK_CONTEXT.get();
        ACTIVE_LINK_CONTEXT.set(new LinkContext(
                programId, shader.getClass().getName(), vertexFormat, bindAttributes));
        try {
            linkAction.run();
        } finally {
            if (previous == null) {
                ACTIVE_LINK_CONTEXT.remove();
            } else {
                ACTIVE_LINK_CONTEXT.set(previous);
            }
            forgetProgram(programId);
        }
    }

    public static void linkOrRestore(int programId, Runnable originalLink) {
        LinkContext context = ACTIVE_LINK_CONTEXT.get();
        if (context == null || context.programId() != programId) {
            originalLink.run();
            return;
        }
        DriverProfile profile = driverProfile();
        if (profile == null) {
            originalLink.run();
            return;
        }
        List<AttributeBinding> boundAttributes = snapshotBoundAttributes(programId);
        if (boundAttributes == null) {
            originalLink.run();
            return;
        }
        AttachedProgramState attached = readAttachedProgramState(programId, profile.capabilities());
        if (attached == null) {
            originalLink.run();
            return;
        }
        String key = attachedContentKey(
                profile.vendor(), profile.renderer(), profile.glVersion(), profile.glslVersion(),
                profile.binaryFormats(), context.shaderClassName(), attached.separable(),
                context.vertexFormat(), context.bindAttributes(), boundAttributes, attached.stages());
        if (restoreByKey(programId, key)) {
            return;
        }
        GL41.glProgramParameteri(programId, GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11.GL_TRUE);
        originalLink.run();
        captureByKey(programId, key);
    }

    public static boolean restoreRaw(
            Shader shader,
            String vertexDigest,
            String fragmentDigest,
            VertexFormat vertexFormat,
            boolean bindAttributes) {
        DriverProfile profile = driverProfile();
        if (profile == null || vertexDigest == null || fragmentDigest == null
                || !matchesDefaultAttributeBindings(shader.getId(), vertexFormat, bindAttributes)) {
            return false;
        }
        String key = contentKey(RAW_SCHEMA, profile.vendor(), profile.renderer(),
                profile.glVersion(), profile.glslVersion(), profile.binaryFormats(),
                vertexDigest, fragmentDigest, vertexFormat, bindAttributes);
        return restoreByKey(shader.getId(), key);
    }

    private static boolean restoreByKey(int programId, String key) {
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
            GL41.glProgramBinary(programId, format, data);
            boolean linked = GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) != 0;
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
            VertexFormat vertexFormat,
            boolean bindAttributes) {
        DriverProfile profile = driverProfile();
        if (profile == null || vertexDigest == null || fragmentDigest == null) {
            return;
        }
        int programId = shader.getId();
        if (!isSimpleTwoStageProgram(programId, profile.capabilities())
                || !matchesDefaultAttributeBindings(programId, vertexFormat, bindAttributes)) {
            return;
        }
        String key = contentKey(RAW_SCHEMA, profile.vendor(), profile.renderer(),
                profile.glVersion(), profile.glslVersion(), profile.binaryFormats(),
                vertexDigest, fragmentDigest, vertexFormat, bindAttributes);
        captureByKey(programId, key);
    }

    private static void captureByKey(int programId, String key) {
        if (key == null || GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == 0) {
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
            if (STORE.put(key, encoded)) {
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

    private static AttachedProgramState readAttachedProgramState(
            int programId, GLCapabilities capabilities) {
        int attachedCount = GL20.glGetProgrami(programId, GL20.GL_ATTACHED_SHADERS);
        if (attachedCount <= 0 || attachedCount > MAX_ATTACHED_SHADERS) {
            return null;
        }
        if (capabilities.OpenGL30
                && GL20.glGetProgrami(programId, GL30.GL_TRANSFORM_FEEDBACK_VARYINGS) != 0) {
            return null;
        }
        boolean separable = capabilities.OpenGL41
                && GL20.glGetProgrami(programId, GL41.GL_PROGRAM_SEPARABLE) != 0;
        List<StageDigest> stages = new ArrayList<>(attachedCount);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer countBuf = stack.mallocInt(1);
            IntBuffer shadersBuf = stack.mallocInt(attachedCount);
            GL20.glGetAttachedShaders(programId, countBuf, shadersBuf);
            int actualCount = countBuf.get(0);
            if (actualCount != attachedCount) {
                return null;
            }
            for (int index = 0; index < actualCount; index++) {
                int shaderId = shadersBuf.get(index);
                int glType = GL20.glGetShaderi(shaderId, GL20.GL_SHADER_TYPE);
                String source = GL20.glGetShaderSource(shaderId);
                if (source == null || source.isEmpty()) {
                    return null;
                }
                stages.add(StageDigest.fromSource(glType, source));
            }
        }
        return new AttachedProgramState(separable, stages);
    }

    private static List<AttributeBinding> snapshotBoundAttributes(int programId) {
        List<AttributeBinding> bindings = TRACKED_BINDINGS.get(programId);
        if (bindings == null) {
            return null;
        }
        synchronized (bindings) {
            return List.copyOf(bindings);
        }
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
            // Drain driver binary rejection errors before falling back to full linking.
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

    static String contentKey(String vendor, String renderer, String glVersion, String glslVersion,
                             int[] binaryFormats, String vertexDigest, String fragmentDigest,
                             VertexFormat vertexFormat, boolean bindAttributes) {
        return contentKey(RAW_SCHEMA, vendor, renderer, glVersion, glslVersion,
                binaryFormats, vertexDigest, fragmentDigest, vertexFormat, bindAttributes);
    }

    static String attachedContentKey(
            String vendor,
            String renderer,
            String glVersion,
            String glslVersion,
            int[] binaryFormats,
            String shaderClassName,
            boolean separable,
            VertexFormat vertexFormat,
            boolean bindAttributes,
            List<AttributeBinding> boundAttributes,
            List<StageDigest> stages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, PROCESSED_SCHEMA);
            update(digest, vendor);
            update(digest, renderer);
            update(digest, glVersion);
            update(digest, glslVersion);
            update(digest, shaderClassName);
            update(digest, Boolean.toString(separable));
            update(digest, Boolean.toString(bindAttributes));
            update(digest, Integer.toString(binaryFormats.length));
            for (int format : binaryFormats) {
                update(digest, Integer.toUnsignedString(format));
            }
            updateVertexFormat(digest, vertexFormat);
            update(digest, Integer.toString(boundAttributes.size()));
            for (AttributeBinding binding : boundAttributes) {
                update(digest, Integer.toString(binding.index()));
                update(digest, binding.name());
            }
            List<StageDigest> sortedStages = new ArrayList<>(stages);
            sortedStages.sort(Comparator.comparingInt(StageDigest::glType)
                    .thenComparing(StageDigest::sourceSha256));
            update(digest, Integer.toString(sortedStages.size()));
            for (StageDigest stage : sortedStages) {
                update(digest, Integer.toUnsignedString(stage.glType()));
                update(digest, stage.sourceSha256());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String contentKey(String schema, String vendor, String renderer, String glVersion, String glslVersion,
                                     int[] binaryFormats, String vertexDigest, String fragmentDigest,
                                     VertexFormat vertexFormat, boolean bindAttributes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, schema);
            update(digest, vendor);
            update(digest, renderer);
            update(digest, glVersion);
            update(digest, glslVersion);
            update(digest, vertexDigest);
            update(digest, fragmentDigest);
            update(digest, Boolean.toString(bindAttributes));
            update(digest, Integer.toString(binaryFormats.length));
            for (int format : binaryFormats) {
                update(digest, Integer.toUnsignedString(format));
            }
            updateVertexFormat(digest, vertexFormat);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateVertexFormat(MessageDigest digest, VertexFormat vertexFormat) {
        var elements = vertexFormat.getElements();
        var attributes = vertexFormat.getElementAttributeNames();
        update(digest, Integer.toString(elements.size()));
        for (int index = 0; index < elements.size(); index++) {
            VertexFormatElement element = elements.get(index);
            update(digest, attributes.get(index));
            update(digest, element.getType().name());
            update(digest, element.getUsage().name());
            update(digest, Integer.toString(element.getIndex()));
            update(digest, Integer.toString(element.getCount()));
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

    private static String sha256(int glType, String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, Integer.toUnsignedString(glType));
            update(digest, source);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    record AttributeBinding(int index, String name) {
    }

    record StageDigest(int glType, String sourceSha256) {
        static StageDigest fromSource(int glType, String source) {
            return new StageDigest(glType, sha256(glType, source));
        }
    }

    private record AttachedProgramState(boolean separable, List<StageDigest> stages) {
    }

    private record LinkContext(
            int programId,
            String shaderClassName,
            VertexFormat vertexFormat,
            boolean bindAttributes) {
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
