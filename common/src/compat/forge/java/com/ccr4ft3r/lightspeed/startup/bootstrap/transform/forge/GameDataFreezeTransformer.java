package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Exposes {@code GameData.loadRegistry} publicly and parallelizes registry snapshot sync,
 * validation, and baking in {@code GameData.freezeData()} while keeping the original
 * {@code BiMap.forEach} instructions intact for ModernFix Mixin targets.
 */
public final class GameDataFreezeTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net.minecraftforge.registries.GameData";
    private static final String TARGET_INTERNAL = "net/minecraftforge/registries/GameData";
    private static final String REGISTRY_MANAGER_INTERNAL = "net/minecraftforge/registries/RegistryManager";
    private static final String REGISTRY_MANAGER_DESC = "Lnet/minecraftforge/registries/RegistryManager;";
    private static final String BIMAP_DESC = "Lcom/google/common/collect/BiMap;";
    private static final String LOAD_REGISTRY_DESC =
            "(Lnet/minecraft/resources/ResourceLocation;" + REGISTRY_MANAGER_DESC + REGISTRY_MANAGER_DESC + "Z)V";
    private static final String SUPPORT_INTERNAL = "com/ccr4ft3r/lightspeed/startup/registry/GameDataFreezeSupport";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name)) {
            return input;
        }
        MethodNode freezeData = null;
        MethodNode loadRegistry = null;
        for (MethodNode method : input.methods) {
            if ("freezeData".equals(method.name) && "()V".equals(method.desc)) {
                freezeData = method;
            } else if ("loadRegistry".equals(method.name) && LOAD_REGISTRY_DESC.equals(method.desc)) {
                loadRegistry = method;
            }
        }
        if (freezeData == null || loadRegistry == null) {
            return input;
        }
        if (!patchFreezeData(freezeData)) {
            return input;
        }
        loadRegistry.access = (loadRegistry.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return Set.of(Target.targetClass(TARGET));
    }

    private static boolean patchFreezeData(MethodNode method) {
        AbstractInsnNode loopStart = null;
        AbstractInsnNode loopEnd = null;
        List<FieldInsnNode> forEachMapLoads = new ArrayList<>(2);
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && SUPPORT_INTERNAL.equals(call.owner)) {
                return false;
            }
            if (loopStart == null
                    && insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && REGISTRY_MANAGER_INTERNAL.equals(field.owner)
                    && "ACTIVE".equals(field.name)) {
                loopStart = insn;
            } else if (loopStart != null && loopEnd == null
                    && insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && REGISTRY_MANAGER_INTERNAL.equals(field.owner)
                    && "FROZEN".equals(field.name)) {
                // Only match the GETSTATIC FROZEN that marks the start of FROZEN.registries.forEach
                AbstractInsnNode next = insn.getNext();
                if (next instanceof FieldInsnNode nextField
                        && nextField.getOpcode() == Opcodes.GETFIELD
                        && REGISTRY_MANAGER_INTERNAL.equals(nextField.owner)
                        && "registries".equals(nextField.name)) {
                    loopEnd = insn;
                    forEachMapLoads.add(nextField);
                }
            } else if (loopEnd != null
                    && insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && REGISTRY_MANAGER_INTERNAL.equals(field.owner)
                    && "registries".equals(field.name)
                    && BIMAP_DESC.equals(field.desc)) {
                forEachMapLoads.add(field);
                break;
            }
        }
        if (loopStart == null || loopEnd == null || forEachMapLoads.size() != 2) {
            return false;
        }

        List<AbstractInsnNode> toRemove = new ArrayList<>();
        for (AbstractInsnNode cur = loopStart; cur != null && cur != loopEnd; cur = cur.getNext()) {
            toRemove.add(cur);
        }
        if (toRemove.isEmpty()) {
            return false;
        }

        InsnList syncInject = new InsnList();
        syncInject.add(new FieldInsnNode(Opcodes.GETSTATIC, REGISTRY_MANAGER_INTERNAL, "ACTIVE", REGISTRY_MANAGER_DESC));
        syncInject.add(new FieldInsnNode(Opcodes.GETFIELD, REGISTRY_MANAGER_INTERNAL, "registries", BIMAP_DESC));
        syncInject.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                SUPPORT_INTERNAL,
                "syncActiveToFrozen",
                "(" + BIMAP_DESC + ")V",
                false
        ));
        method.instructions.insertBefore(loopStart, syncInject);
        for (AbstractInsnNode node : toRemove) {
            method.instructions.remove(node);
        }

        for (FieldInsnNode mapLoad : forEachMapLoads) {
            method.instructions.insert(mapLoad, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    SUPPORT_INTERNAL,
                    "parallelForEachView",
                    "(" + BIMAP_DESC + ")" + BIMAP_DESC,
                    false
            ));
        }

        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = Math.max(method.maxStack, 4);
        return true;
    }
}
