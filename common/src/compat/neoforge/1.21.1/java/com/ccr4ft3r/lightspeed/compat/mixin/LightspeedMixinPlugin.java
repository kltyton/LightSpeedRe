package com.ccr4ft3r.lightspeed.compat.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Installs known companion callbacks and gates ModernFix ModifyBakingResult parallel dispatch. */
public final class LightspeedMixinPlugin implements IMixinConfigPlugin {
    private static final String CLIENT_HOOKS_MODIFY_BAKING_MIXIN =
            "com.ccr4ft3r.lightspeed.mixin.client.ClientHooksModifyBakingMixin";
    private static final String BASE_MAP_CODEC_MIXIN =
            "com.ccr4ft3r.lightspeed.mixin.misc.BaseMapCodecMixin";
    private static final String ARRAY_MAP_INTERNAL =
            "it/unimi/dsi/fastutil/objects/Object2ObjectArrayMap";
    private static final String LINKED_MAP_INTERNAL =
            "it/unimi/dsi/fastutil/objects/Object2ObjectLinkedOpenHashMap";
    private static final String EXPECTED_MODIFY_BAKING_INDY_DESC =
            "(Lorg/embeddedt/modernfix/neoforge/dynresources/ModelBakeEventHelper;"
                    + "Lnet/neoforged/neoforge/client/event/ModelEvent$ModifyBakingResult;"
                    + "Ljava/util/Map;Ljava/lang/reflect/Method;)Ljava/util/function/BiConsumer;";

    private Boolean modernFixModelBakePresent;
    private String emfModelSelector;

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (CLIENT_HOOKS_MODIFY_BAKING_MIXIN.equals(mixinClassName)) {
            return hasModernFixModelBakeHelper();
        }
        if (mixinClassName.startsWith("com.ccr4ft3r.lightspeed.mixin.compat.emf.")) {
            if (emfModelSelector == null) {
                try {
                    ClassNode manager = MixinService.getService().getBytecodeProvider()
                            .getClassNode(targetClassName);
                    emfModelSelector = manager.fields.stream()
                            .filter(field -> field.name.equals("currentSpecifiedModelLoading"))
                            .map(field -> field.desc)
                            .findFirst().orElseThrow(() -> new IllegalStateException("EMF model selector is missing"));
                } catch (ClassNotFoundException exception) {
                    return false;
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot read EMF's model selector ABI", exception);
                }
            }
            return switch (emfModelSelector) {
                case "Ljava/lang/String;" -> mixinClassName.endsWith(".EmfModelLoadingContextMixin");
                case "Ljava/lang/ThreadLocal;" -> mixinClassName.endsWith(".EmfThreadLocalModelLoadingContextMixin");
                default -> throw new IllegalStateException("Unsupported EMF model selector " + emfModelSelector);
            };
        }
        return true;
    }

    @Override public List<String> getMixins() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        if (otherTargets.contains("com.brandon3055.draconicevolution.blocks.tileentity.StabilizedSpawnerLogic")) {
            JdteSpawnerAccessorCallbacks.install();
        }
    }

    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
        if (BASE_MAP_CODEC_MIXIN.equals(mixinClassName)) {
            patchBaseMapCodecAccumulator(targetClass);
            return;
        }
        if (!CLIENT_HOOKS_MODIFY_BAKING_MIXIN.equals(mixinClassName)) {
            return;
        }
        for (MethodNode method : targetClass.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof InvokeDynamicInsnNode indy
                        && "accept".equals(indy.name)
                        && indy.desc.endsWith(")Ljava/util/function/BiConsumer;")
                        && indy.desc.contains("Lorg/embeddedt/modernfix/neoforge/dynresources/ModelBakeEventHelper;")) {
                    AbstractInsnNode next = indy.getNext();
                    while (next != null && (next.getType() == AbstractInsnNode.LABEL
                            || next.getType() == AbstractInsnNode.LINE
                            || next.getType() == AbstractInsnNode.FRAME)) {
                        next = next.getNext();
                    }
                    if (EXPECTED_MODIFY_BAKING_INDY_DESC.equals(indy.desc)
                            && next instanceof MethodInsnNode forEachCall
                            && forEachCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "net/neoforged/fml/ModList".equals(forEachCall.owner)
                            && "forEachModContainer".equals(forEachCall.name)
                            && "(Ljava/util/function/BiConsumer;)V".equals(forEachCall.desc)) {
                        // The original consumer owns ModernFix's registry wrapper and event callbacks.
                        // Its captured timing map is mutable and is shared between the parallel calls.
                        method.instructions.insertBefore(indy, new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
                        method.instructions.insertBefore(indy, new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "java/util/Collections",
                                "synchronizedMap",
                                "(Ljava/util/Map;)Ljava/util/Map;",
                                false
                        ));
                        method.instructions.insertBefore(indy, new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
                        method.instructions.set(forEachCall, new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "com/ccr4ft3r/lightspeed/startup/client/ModelBakeHelperSupport",
                                "dispatchModifyBakingResult",
                                "(Lnet/neoforged/fml/ModList;Ljava/util/function/BiConsumer;)V",
                                false
                        ));
                        return;
                    }
                    // Unknown captured shape: leave ModernFix's original consumer intact.
                    return;
                }
            }
        }
    }

    private boolean hasModernFixModelBakeHelper() {
        if (modernFixModelBakePresent == null) {
            try {
                MixinService.getService().getBytecodeProvider()
                        .getClassNode("org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper");
                modernFixModelBakePresent = true;
            } catch (ClassNotFoundException | IOException ignored) {
                modernFixModelBakePresent = false;
            }
        }
        return modernFixModelBakePresent;
    }

    private static void patchBaseMapCodecAccumulator(ClassNode targetClass) {
        for (MethodNode method : targetClass.methods) {
            if (!"decode".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode type
                        && ARRAY_MAP_INTERNAL.equals(type.desc)) {
                    type.desc = LINKED_MAP_INTERNAL;
                } else if (insn.getOpcode() == Opcodes.INVOKESPECIAL && insn instanceof MethodInsnNode call
                        && ARRAY_MAP_INTERNAL.equals(call.owner)
                        && "<init>".equals(call.name) && "()V".equals(call.desc)) {
                    call.owner = LINKED_MAP_INTERNAL;
                }
            }
        }
    }
}
