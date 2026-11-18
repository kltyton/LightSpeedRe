package com.ccr4ft3r.lightspeed.compat.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Selects compatible companion hooks and patches the DFU map accumulator. */
public final class LightspeedMixinPlugin implements IMixinConfigPlugin {
    private static final String BASE_MAP_CODEC_MIXIN =
            "com.ccr4ft3r.lightspeed.mixin.misc.BaseMapCodecMixin";
    private static final String ARRAY_MAP_INTERNAL =
            "it/unimi/dsi/fastutil/objects/Object2ObjectArrayMap";
    private static final String LINKED_MAP_INTERNAL =
            "it/unimi/dsi/fastutil/objects/Object2ObjectLinkedOpenHashMap";
    private String emfModelSelector;

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
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
        }
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
