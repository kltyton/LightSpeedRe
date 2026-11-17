package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Replaces the per-state {@code Stream.collect(ImmutableMap.toImmutableMap(...))} allocation in
 * {@code StateDefinition.createState} with a direct indexed builder helper.
 */
public final class StateDefinitionTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net.minecraft.world.level.block.state.StateDefinition";
    private static final String TARGET_INTERNAL = "net/minecraft/world/level/block/state/StateDefinition";
    private static final String METHOD_DESC =
            "(Lnet/minecraft/world/level/block/state/StateDefinition$Factory;Ljava/lang/Object;Lcom/mojang/serialization/MapCodec;Ljava/util/Map;Ljava/util/List;Ljava/util/List;)V";
    private static final String SUPPORT_INTERNAL = "com/ccr4ft3r/lightspeed/startup/compat/StateDefinitionSupport";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name)) {
            return input;
        }
        for (MethodNode method : input.methods) {
            if (("m_61057_".equals(method.name) || "createState".equals(method.name))
                    && METHOD_DESC.equals(method.desc)
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                patchCreateState(method);
                break;
            }
        }
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

    private static void patchCreateState(MethodNode method) {
        AbstractInsnNode storeNode = null;
        boolean sawCollect = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && "java/util/stream/Stream".equals(call.owner)
                    && "collect".equals(call.name)) {
                sawCollect = true;
            } else if (sawCollect && insn.getOpcode() == Opcodes.ASTORE && insn instanceof VarInsnNode varInsn && varInsn.var == 6) {
                storeNode = insn;
                break;
            } else if (insn instanceof MethodInsnNode call && SUPPORT_INTERNAL.equals(call.owner)) {
                return;
            }
        }
        if (storeNode == null) {
            return;
        }
        AbstractInsnNode cursor = method.instructions.getFirst();
        while (cursor != null && cursor != storeNode) {
            AbstractInsnNode next = cursor.getNext();
            if (!(cursor instanceof org.objectweb.asm.tree.LabelNode)
                    && !(cursor instanceof org.objectweb.asm.tree.LineNumberNode)) {
                method.instructions.remove(cursor);
            }
            cursor = next;
        }
        InsnList direct = new InsnList();
        direct.add(new VarInsnNode(Opcodes.ALOAD, 5));
        direct.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                SUPPORT_INTERNAL,
                "toPropertyMap",
                "(Ljava/util/List;)Lcom/google/common/collect/ImmutableMap;",
                false
        ));
        method.instructions.insertBefore(storeNode, direct);
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
    }
}
