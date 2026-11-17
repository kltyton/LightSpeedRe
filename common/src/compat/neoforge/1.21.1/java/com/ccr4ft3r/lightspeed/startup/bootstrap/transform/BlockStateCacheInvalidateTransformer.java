package com.ccr4ft3r.lightspeed.startup.bootstrap.transform;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parallelizes ModernFix 1.21.1 {@code BlockStateCacheHandler.invalidateCache()} by routing the
 * synchronized {@code Block.BLOCK_STATE_REGISTRY} sweep through {@link com.ccr4ft3r.lightspeed.startup.compat.BlockStateCacheInvalidateSupport}.
 */
public final class BlockStateCacheInvalidateTransformer extends CompatClassTransformer {
    private static final String TARGET = "org.embeddedt.modernfix.blockstate.BlockStateCacheHandler";
    private static final String TARGET_INTERNAL = "org/embeddedt/modernfix/blockstate/BlockStateCacheHandler";
    private static final String SUPPORT_INTERNAL = "com/ccr4ft3r/lightspeed/startup/compat/BlockStateCacheInvalidateSupport";
    private static final String BLOCK_INTERNAL = "net/minecraft/world/level/block/Block";
    private static final String ID_MAPPER_INTERNAL = "net/minecraft/core/IdMapper";
    private static final String IBLOCKSTATE_INTERNAL = "org/embeddedt/modernfix/duck/IBlockState";
    private static final String CLEAR_HELPER = "lightspeed$clearState";

    private static final Handle LAMBDA_METAFACTORY = new Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;",
            false
    );

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name)) {
            return input;
        }
        MethodNode invalidate = null;
        for (MethodNode m : input.methods) {
            if ("invalidateCache".equals(m.name) && "()V".equals(m.desc)) {
                invalidate = m;
                break;
            }
        }
        if (invalidate == null) {
            return input;
        }

        FieldInsnNode registryGet = null;
        MethodInsnNode clearCall = null;
        for (AbstractInsnNode insn = invalidate.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (registryGet == null
                    && insn instanceof FieldInsnNode f
                    && f.getOpcode() == Opcodes.GETSTATIC
                    && BLOCK_INTERNAL.equals(f.owner)
                    && "BLOCK_STATE_REGISTRY".equals(f.name)) {
                registryGet = f;
            } else if (registryGet != null
                    && insn instanceof MethodInsnNode m
                    && m.getOpcode() == Opcodes.INVOKEINTERFACE
                    && IBLOCKSTATE_INTERNAL.equals(m.owner)
                    && "clearCache".equals(m.name)
                    && "()V".equals(m.desc)) {
                clearCall = m;
                break;
            }
        }
        if (registryGet == null || clearCall == null) {
            return input;
        }

        AbstractInsnNode monitorExit = null;
        for (AbstractInsnNode cur = clearCall.getNext(); cur != null; cur = cur.getNext()) {
            if (cur.getOpcode() == Opcodes.MONITOREXIT) {
                monitorExit = cur;
                break;
            }
        }
        AbstractInsnNode beforeMonitorExit = monitorExit == null ? null : TransformAsmHelper.previousRealInsn(monitorExit);
        if (beforeMonitorExit == null || beforeMonitorExit == registryGet) {
            return input;
        }

        List<AbstractInsnNode> toRemove = new ArrayList<>();
        for (AbstractInsnNode cur = registryGet.getNext(); cur != null && cur != beforeMonitorExit; cur = cur.getNext()) {
            if (cur.getType() != AbstractInsnNode.LABEL && cur.getType() != AbstractInsnNode.LINE) {
                toRemove.add(cur);
            }
        }

        boolean hasHelper = false;
        for (MethodNode m : input.methods) {
            if (CLEAR_HELPER.equals(m.name) && "(Ljava/lang/Object;)V".equals(m.desc)) {
                hasHelper = true;
                break;
            }
        }
        if (!hasHelper) {
            MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    CLEAR_HELPER, "(Ljava/lang/Object;)V", null, null);
            InsnList hInsns = helper.instructions;
            hInsns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hInsns.add(new TypeInsnNode(Opcodes.CHECKCAST, IBLOCKSTATE_INTERNAL));
            hInsns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, IBLOCKSTATE_INTERNAL, "clearCache", "()V", true));
            hInsns.add(new InsnNode(Opcodes.RETURN));
            helper.maxStack = 1;
            helper.maxLocals = 1;
            input.methods.add(helper);
        }

        InsnList call = new InsnList();
        call.add(new InvokeDynamicInsnNode(
                "accept",
                "()Ljava/util/function/Consumer;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("(Ljava/lang/Object;)V"),
                new Handle(Opcodes.H_INVOKESTATIC, TARGET_INTERNAL, CLEAR_HELPER, "(Ljava/lang/Object;)V", false),
                Type.getMethodType("(Ljava/lang/Object;)V")
        ));
        call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                SUPPORT_INTERNAL,
                "invalidateIdMapper",
                "(L" + ID_MAPPER_INTERNAL + ";Ljava/util/function/Consumer;)V",
                false
        ));
        invalidate.instructions.insert(registryGet, call);
        for (AbstractInsnNode node : toRemove) {
            invalidate.instructions.remove(node);
        }
        if (invalidate.localVariables != null) {
            invalidate.localVariables.clear();
        }
        invalidate.maxStack = Math.max(invalidate.maxStack, 3);
        return input;
    }

    @Override
    protected Set<String> targetClassNames() {
        return Set.of(TARGET);
    }
}
