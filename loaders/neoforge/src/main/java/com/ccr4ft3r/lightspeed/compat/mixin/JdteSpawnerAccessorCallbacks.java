package com.ccr4ft3r.lightspeed.compat.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Preserves JDTE's Object getter ABI while generating a read of the actual typed tile field. */
final class JdteSpawnerAccessorCallbacks implements IMixinConfigPlugin {
    private static final String TARGET = "com.brandon3055.draconicevolution.blocks.tileentity.StabilizedSpawnerLogic";
    private static final String ACCESSOR = "com.jdte.mixin.StabilizedSpawnerLogicAccessor";
    private static final String TILE = "Lcom/brandon3055/draconicevolution/blocks/tileentity/TileStabilizedSpawner;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String ACCESSOR_HASH = "672703c7d493ca6f3d18ac941b8fa48bb6a88da60537157f7cefe5b53dba622d";

    private final IMixinConfigPlugin delegate;
    // MixinProcessor serializes application; aliases live only between its pre/post callbacks.
    private final Map<ClassNode, FieldNode> aliases = new IdentityHashMap<>();

    private JdteSpawnerAccessorCallbacks(IMixinConfigPlugin delegate) {
        this.delegate = delegate;
    }

    static void install() {
        // Mixin has no public setter for another configuration's companion. These 0.8.7
        // fields are accessed before class application, without retransformation or agents.
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            Object processor = readField(transformer, "processor");
            List<?> pending = (List<?>) readField(processor, "pendingConfigs");
            List<?> active = (List<?>) readField(processor, "configs");
            List<Object> configurations = new ArrayList<>(pending);
            configurations.addAll(active);
            for (Object configuration : configurations) {
                Method getName = configuration.getClass().getDeclaredMethod("getName");
                getName.setAccessible(true);
                if (!"mixins.jdte.json".equals(getName.invoke(configuration))) continue;
                Object handle = readField(configuration, "plugin");
                Field plugin = handle.getClass().getDeclaredField("plugin");
                plugin.setAccessible(true);
                IMixinConfigPlugin original = (IMixinConfigPlugin) plugin.get(handle);
                if (!(original instanceof JdteSpawnerAccessorCallbacks)) {
                    plugin.set(handle, new JdteSpawnerAccessorCallbacks(original));
                    MixinService.getService().getLogger("Lightspeed").info("Installed JDTE typed spawner getter compatibility");
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not install JDTE spawner accessor callbacks", exception);
        }
    }

    private static Object readField(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static boolean matches(String target, String mixin, IMixinInfo info) {
        if (!TARGET.equals(target) || !ACCESSOR.equals(mixin)) return false;
        try (InputStream bytes = MixinService.getService().getResourceAsStream(ACCESSOR.replace('.', '/') + ".class")) {
            if (bytes == null) throw new IllegalStateException("JDTE spawner accessor bytecode is unavailable");
            return ACCESSOR_HASH.equals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("Could not identify JDTE spawner accessor bytecode", exception);
        }
    }

    @Override
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (delegate != null) delegate.preApply(target, node, mixin, info);
        if (!matches(target, mixin, info)) return;
        FieldNode tile = node.fields.stream().filter(field -> field.name.equals("tile") && field.desc.equals(TILE))
                .findFirst().orElseThrow(() -> new IllegalStateException("Draconic spawner tile field no longer matches JDTE compatibility"));
        FieldNode alias = new FieldNode(tile.access, tile.name, OBJECT, null, null);
        node.fields.add(alias);
        aliases.put(node, alias);
    }

    @Override
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (TARGET.equals(target) && ACCESSOR.equals(mixin)) {
            FieldNode alias = aliases.remove(node);
            if (alias != null) {
                try {
                    MethodNode getter = node.methods.stream()
                            .filter(method -> method.name.equals("jdte$tile") && method.desc.equals("()" + OBJECT))
                            .findFirst().orElseThrow(() -> new IllegalStateException("JDTE spawner getter was not generated"));
                    int reads = 0;
                    for (AbstractInsnNode instruction : getter.instructions) {
                        if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                                && field.owner.equals(node.name) && field.name.equals("tile") && field.desc.equals(OBJECT)) {
                            field.desc = TILE;
                            reads++;
                        }
                    }
                    if (reads != 1) throw new IllegalStateException("Unexpected JDTE spawner getter reads: " + reads);
                } finally {
                    node.fields.remove(alias);
                }
            }
        }
        if (delegate != null) delegate.postApply(target, node, mixin, info);
    }

    @Override public void onLoad(String mixinPackage) { if (delegate != null) delegate.onLoad(mixinPackage); }
    @Override public String getRefMapperConfig() { return delegate == null ? null : delegate.getRefMapperConfig(); }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        return delegate == null || delegate.shouldApplyMixin(target, mixin);
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> other) {
        if (delegate != null) delegate.acceptTargets(mine, other);
    }
    @Override public List<String> getMixins() { return delegate == null ? null : delegate.getMixins(); }
}
