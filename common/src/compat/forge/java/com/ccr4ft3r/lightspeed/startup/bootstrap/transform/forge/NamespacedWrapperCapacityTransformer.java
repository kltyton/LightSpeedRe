package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Prevents quadratic {@code ObjectArrays.forceCapacity} copies in {@code NamespacedWrapper.onAdded}
 * by exponentially pre-expanding the backing {@code ObjectArrayList} via {@code ensureCapacity}
 * before {@code ObjectList.size(int)} runs.
 */
public final class NamespacedWrapperCapacityTransformer implements ITransformer<ClassNode> {
    private static final String TARGET_INTERNAL = "net/minecraftforge/registries/NamespacedWrapper";
    private static final String ON_ADDED_NAME = "onAdded";
    private static final String ON_ADDED_DESC = "(Lnet/minecraftforge/registries/RegistryManager;ILnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Ljava/lang/Object;)Lnet/minecraft/core/Holder$Reference;";
    private static final String OBJECT_LIST_INTERNAL = "it/unimi/dsi/fastutil/objects/ObjectList";
    private static final String OBJECT_ARRAY_LIST_INTERNAL = "it/unimi/dsi/fastutil/objects/ObjectArrayList";
    private static final String ENSURE_HELPER = "lightspeed$ensureHolderCapacity";
    private static final String ENSURE_DESC = "(Lit/unimi/dsi/fastutil/objects/ObjectList;I)V";
    private static final int INITIAL_HOLDER_CAPACITY = 256;
    private static final int MAX_EXPONENTIAL_SIZE = 1 << 29;

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name) || hasHelper(input)) {
            return input;
        }
        MethodNode onAdded = findOnAdded(input);
        if (onAdded == null) {
            return input;
        }

        boolean patched = false;
        for (AbstractInsnNode insn = onAdded.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && OBJECT_LIST_INTERNAL.equals(call.owner)
                    && "size".equals(call.name)
                    && "(I)V".equals(call.desc)) {
                InsnList pre = new InsnList();
                pre.add(new InsnNode(Opcodes.DUP2));
                pre.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        TARGET_INTERNAL,
                        ENSURE_HELPER,
                        ENSURE_DESC,
                        false
                ));
                onAdded.instructions.insertBefore(call, pre);
                onAdded.maxStack = Math.max(onAdded.maxStack, 6);
                patched = true;
                break;
            }
        }
        if (patched) {
            input.methods.add(createEnsureHelper());
        }
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return Set.of(Target.targetClass("net.minecraftforge.registries.NamespacedWrapper"));
    }

    private static boolean hasHelper(ClassNode input) {
        for (MethodNode method : input.methods) {
            if (ENSURE_HELPER.equals(method.name) && ENSURE_DESC.equals(method.desc)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode findOnAdded(ClassNode input) {
        for (MethodNode method : input.methods) {
            if (ON_ADDED_NAME.equals(method.name) && ON_ADDED_DESC.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static MethodNode createEnsureHelper() {
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                ENSURE_HELPER,
                ENSURE_DESC,
                null,
                null
        );

        InsnList insns = new InsnList();
        LabelNode earlyReturn = new LabelNode();
        LabelNode computeCurrentCap = new LabelNode();
        LabelNode haveCurrentCap = new LabelNode();
        LabelNode fullReturn = new LabelNode();

        // Early bailout before initializing locals 2..5 so frame shapes remain trivial.
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new TypeInsnNode(Opcodes.INSTANCEOF, OBJECT_ARRAY_LIST_INTERNAL));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, earlyReturn));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new IntInsnNode(Opcodes.SIPUSH, INITIAL_HOLDER_CAPACITY));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, earlyReturn));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new LdcInsnNode(MAX_EXPONENTIAL_SIZE));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGT, earlyReturn));

        // ObjectArrayList list = (ObjectArrayList) holdersById;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, OBJECT_ARRAY_LIST_INTERNAL));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));

        // int current = list.size();
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OBJECT_ARRAY_LIST_INTERNAL, "size", "()I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 3));

        // if (minSize <= current) return;
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, fullReturn));

        // int targetCap = Integer.highestOneBit(minSize - 1) << 1;
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "highestOneBit", "(I)I", false));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 4));

        // int currentCap = current <= 256 ? 256 : (Integer.highestOneBit(current - 1) << 1);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new IntInsnNode(Opcodes.SIPUSH, INITIAL_HOLDER_CAPACITY));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGT, computeCurrentCap));
        insns.add(new IntInsnNode(Opcodes.SIPUSH, INITIAL_HOLDER_CAPACITY));
        insns.add(new JumpInsnNode(Opcodes.GOTO, haveCurrentCap));

        insns.add(computeCurrentCap);
        insns.add(new FrameNode(
                Opcodes.F_APPEND,
                3,
                new Object[]{OBJECT_ARRAY_LIST_INTERNAL, Opcodes.INTEGER, Opcodes.INTEGER},
                0,
                null
        ));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "highestOneBit", "(I)I", false));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.ISHL));

        insns.add(haveCurrentCap);
        insns.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{Opcodes.INTEGER}));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 5));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, fullReturn));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OBJECT_ARRAY_LIST_INTERNAL, "ensureCapacity", "(I)V", false));

        insns.add(fullReturn);
        insns.add(new FrameNode(
                Opcodes.F_FULL,
                4,
                new Object[]{OBJECT_LIST_INTERNAL, Opcodes.INTEGER, OBJECT_ARRAY_LIST_INTERNAL, Opcodes.INTEGER},
                0,
                new Object[0]
        ));
        insns.add(new InsnNode(Opcodes.RETURN));

        insns.add(earlyReturn);
        insns.add(new FrameNode(
                Opcodes.F_FULL,
                2,
                new Object[]{OBJECT_LIST_INTERNAL, Opcodes.INTEGER},
                0,
                new Object[0]
        ));
        insns.add(new InsnNode(Opcodes.RETURN));

        helper.instructions = insns;
        helper.maxStack = 2;
        helper.maxLocals = 6;
        return helper;
    }
}
