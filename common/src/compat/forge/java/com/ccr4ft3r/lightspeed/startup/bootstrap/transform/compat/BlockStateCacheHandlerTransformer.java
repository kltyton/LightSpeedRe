package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Parallelizes ModernFix {@code BlockStateCacheHandler.rebuildParallel} cache invalidation
 * across workers while keeping the exact {@code BlockBehaviour$BlockStateBase} class monitor.
 */
public final class BlockStateCacheHandlerTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "org.embeddedt.modernfix.blockstate.BlockStateCacheHandler";
    private static final String TARGET_INTERNAL = "org/embeddedt/modernfix/blockstate/BlockStateCacheHandler";
    private static final String BLOCK_INTERNAL = "net/minecraft/world/level/block/Block";
    private static final String BLOCK_STATE_BASE_INTERNAL = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String SUPPORT_INTERNAL = "com/ccr4ft3r/lightspeed/startup/compat/BlockStateCacheParallelSupport";
    private static final String CONSUMER_INTERNAL = SUPPORT_INTERNAL + "$RangeConsumer";
    private static final String HELPER_METHOD = "lightspeed$clearRange";
    private static final String HELPER_DESC = "(Ljava/util/List;II)V";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name)) {
            return input;
        }
        MethodNode rebuild = findRebuildMethod(input);
        if (rebuild == null) {
            return input;
        }
        FieldInsnNode registryField = findBlockRegistryField(rebuild);
        MethodInsnNode clearCall = findClearCacheCall(rebuild);
        if (registryField == null || clearCall == null) {
            return input;
        }
        ensureClearRangeMethod(input, clearCall.owner, clearCall.name);
        rewriteRebuildMethod(rebuild, registryField);
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

    private static MethodNode findRebuildMethod(ClassNode input) {
        for (MethodNode m : input.methods) {
            if ("rebuildParallel".equals(m.name) && "(Z)V".equals(m.desc)
                    && (m.access & Opcodes.ACC_STATIC) != 0) {
                return m;
            }
        }
        return null;
    }

    private static FieldInsnNode findBlockRegistryField(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
                    && BLOCK_INTERNAL.equals(field.owner)) {
                return field;
            }
        }
        return null;
    }

    private static MethodInsnNode findClearCacheCall(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.INVOKEINTERFACE && insn instanceof MethodInsnNode call
                    && "clearCache".equals(call.name) && "()V".equals(call.desc)) {
                return call;
            }
        }
        return null;
    }

    private static void ensureClearRangeMethod(ClassNode input, String iBlockStateInternal, String clearMethodName) {
        for (MethodNode m : input.methods) {
            if (HELPER_METHOD.equals(m.name) && HELPER_DESC.equals(m.desc)) {
                return;
            }
        }
        MethodNode m = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                HELPER_METHOD,
                HELPER_DESC,
                null,
                null
        );
        InsnList insns = m.instructions;
        LabelNode loopLabel = new LabelNode(new Label());
        LabelNode skipNullLabel = new LabelNode(new Label());
        LabelNode nextLabel = new LabelNode(new Label());
        LabelNode doneLabel = new LabelNode(new Label());

        // int i = start;
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 3));

        insns.add(loopLabel);
        insns.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{Opcodes.INTEGER}, 0, null));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, doneLabel));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, skipNullLabel));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, iBlockStateInternal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, iBlockStateInternal, clearMethodName, "()V", true));
        insns.add(new JumpInsnNode(Opcodes.GOTO, nextLabel));

        insns.add(skipNullLabel);
        insns.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Object"}));
        insns.add(new InsnNode(Opcodes.POP));

        insns.add(nextLabel);
        insns.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        insns.add(new IincInsnNodeCompat(3, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));

        insns.add(doneLabel);
        insns.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        insns.add(new InsnNode(Opcodes.RETURN));

        m.maxStack = 2;
        m.maxLocals = 4;
        input.methods.add(m);
    }

    private static void rewriteRebuildMethod(MethodNode rebuild, FieldInsnNode registryField) {
        InsnList rewritten = new InsnList();
        LabelNode tryStart = new LabelNode(new Label());
        LabelNode tryEnd = new LabelNode(new Label());
        LabelNode catchStart = new LabelNode(new Label());
        LabelNode catchEnd = new LabelNode(new Label());
        LabelNode returnLabel = new LabelNode(new Label());

        rewritten.add(new LdcInsnNode(Type.getObjectType(BLOCK_STATE_BASE_INTERNAL)));
        rewritten.add(new InsnNode(Opcodes.DUP));
        rewritten.add(new VarInsnNode(Opcodes.ASTORE, 1));
        rewritten.add(new InsnNode(Opcodes.MONITORENTER));

        rewritten.add(tryStart);
        rewritten.add(new FieldInsnNode(Opcodes.GETSTATIC, registryField.owner, registryField.name, registryField.desc));
        Handle metafactory = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false
        );
        Handle target = new Handle(
                Opcodes.H_INVOKESTATIC,
                TARGET_INTERNAL,
                HELPER_METHOD,
                HELPER_DESC,
                false
        );
        rewritten.add(new InvokeDynamicInsnNode(
                "accept",
                "()L" + CONSUMER_INTERNAL + ";",
                metafactory,
                Type.getMethodType(HELPER_DESC),
                target,
                Type.getMethodType(HELPER_DESC)
        ));
        rewritten.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                SUPPORT_INTERNAL,
                "clearBlockStateCaches",
                "(Ljava/lang/Object;L" + CONSUMER_INTERNAL + ";)V",
                false
        ));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rewritten.add(new InsnNode(Opcodes.MONITOREXIT));
        rewritten.add(tryEnd);
        rewritten.add(new JumpInsnNode(Opcodes.GOTO, returnLabel));

        rewritten.add(catchStart);
        rewritten.add(new FrameNode(Opcodes.F_FULL, 2, new Object[]{Opcodes.INTEGER, "java/lang/Object"}, 1, new Object[]{"java/lang/Throwable"}));
        rewritten.add(new VarInsnNode(Opcodes.ASTORE, 2));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rewritten.add(new InsnNode(Opcodes.MONITOREXIT));
        rewritten.add(catchEnd);
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 2));
        rewritten.add(new InsnNode(Opcodes.ATHROW));

        rewritten.add(returnLabel);
        rewritten.add(new FrameNode(Opcodes.F_FULL, 1, new Object[]{Opcodes.INTEGER}, 0, new Object[0]));
        rewritten.add(new InsnNode(Opcodes.RETURN));

        rebuild.instructions.clear();
        rebuild.instructions.add(rewritten);
        rebuild.tryCatchBlocks.clear();
        rebuild.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchStart, null));
        rebuild.tryCatchBlocks.add(new TryCatchBlockNode(catchStart, catchEnd, catchStart, null));
        if (rebuild.localVariables != null) {
            rebuild.localVariables.clear();
        }
        rebuild.maxStack = 2;
        rebuild.maxLocals = 3;
    }

    private static final class IincInsnNodeCompat extends org.objectweb.asm.tree.IincInsnNode {
        private IincInsnNodeCompat(int var, int incr) {
            super(var, incr);
        }
    }
}
