package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Speeds up FerriteCore {@code FastMap} property lookups during state initialization:
 * sizes {@code toKeyIndex} from the property collection, checks {@code keys} by pointer identity first in
 * {@code getKeyFor}, and replaces {@code ImmutableList.iterator()} in {@code getIndexOf} with indexed access.
 */
public final class FastMapTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "malte0811.ferritecore.fastmap.FastMap";
    private static final String TARGET_INTERNAL = "malte0811/ferritecore/fastmap/FastMap";
    private static final String FAST_MAP_KEY = "malte0811/ferritecore/fastmap/FastMapKey";
    private static final String PROPERTY_INTERNAL = "net/minecraft/world/level/block/state/properties/Property";
    private static final String HASH_MAP_INTERNAL = "it/unimi/dsi/fastutil/objects/Object2IntOpenHashMap";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name)) {
            return input;
        }
        for (MethodNode method : input.methods) {
            if ("<init>".equals(method.name)
                    && "(Ljava/util/Collection;Ljava/util/Map;Z)V".equals(method.desc)) {
                sizeToKeyIndexMap(method);
            } else if ("getKeyFor".equals(method.name)
                    && ("(L" + PROPERTY_INTERNAL + ";)L" + FAST_MAP_KEY + ";").equals(method.desc)) {
                patchGetKeyFor(method);
            } else if ("getIndexOf".equals(method.name)
                    && "(Ljava/util/Map;)I".equals(method.desc)) {
                patchGetIndexOf(method);
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

    private static void sizeToKeyIndexMap(MethodNode ctor) {
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && HASH_MAP_INTERNAL.equals(call.owner)
                    && "<init>".equals(call.name)
                    && "()V".equals(call.desc)) {
                InsnList sizeArg = new InsnList();
                sizeArg.add(new VarInsnNode(Opcodes.ALOAD, 1));
                sizeArg.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/Collection",
                        "size",
                        "()I",
                        true
                ));
                ctor.instructions.insertBefore(call, sizeArg);
                call.desc = "(I)V";
                ctor.maxStack = Math.max(ctor.maxStack, 4);
                return;
            }
        }
    }

    private static void patchGetKeyFor(MethodNode method) {
        if (method.instructions.size() == 0) {
            return;
        }
        LabelNode loopLabel = new LabelNode();
        LabelNode nextLabel = new LabelNode();
        LabelNode fallbackLabel = new LabelNode();

        InsnList fastPath = new InsnList();
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, "keys", "Ljava/util/List;"));
        fastPath.add(new VarInsnNode(Opcodes.ASTORE, 2));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fastPath.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        fastPath.add(new VarInsnNode(Opcodes.ISTORE, 3));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 3));
        fastPath.add(new IntInsnNode(Opcodes.BIPUSH, 8));
        fastPath.add(new JumpInsnNode(Opcodes.IF_ICMPGT, fallbackLabel));
        fastPath.add(new InsnNode(Opcodes.ICONST_0));
        fastPath.add(new VarInsnNode(Opcodes.ISTORE, 4));

        fastPath.add(loopLabel);
        fastPath.add(new FrameNode(
                Opcodes.F_FULL,
                5,
                new Object[]{TARGET_INTERNAL, PROPERTY_INTERNAL, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                0,
                null
        ));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 4));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 3));
        fastPath.add(new JumpInsnNode(Opcodes.IF_ICMPGE, fallbackLabel));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 4));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "get",
                "(I)Ljava/lang/Object;",
                true
        ));
        fastPath.add(new TypeInsnNode(Opcodes.CHECKCAST, FAST_MAP_KEY));
        fastPath.add(new VarInsnNode(Opcodes.ASTORE, 5));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 5));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                FAST_MAP_KEY,
                "getProperty",
                "()L" + PROPERTY_INTERNAL + ";",
                false
        ));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastPath.add(new JumpInsnNode(Opcodes.IF_ACMPNE, nextLabel));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 5));
        fastPath.add(new InsnNode(Opcodes.ARETURN));

        fastPath.add(nextLabel);
        fastPath.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        fastPath.add(new IincInsnNode(4, 1));
        fastPath.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));

        fastPath.add(fallbackLabel);
        fastPath.add(new FrameNode(
                Opcodes.F_FULL,
                2,
                new Object[]{TARGET_INTERNAL, PROPERTY_INTERNAL},
                0,
                null
        ));

        method.instructions.insert(fastPath);
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = Math.max(method.maxStack, 3);
        method.maxLocals = Math.max(method.maxLocals, 6);
    }

    private static void patchGetIndexOf(MethodNode method) {
        String partialMethodDesc = "(Ljava/lang/Comparable;)I";
        String partialMethodName = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && FAST_MAP_KEY.equals(call.owner)
                    && partialMethodDesc.equals(call.desc)) {
                partialMethodName = call.name;
                break;
            }
        }
        if (partialMethodName == null) {
            return;
        }

        LabelNode loopLabel = new LabelNode();
        LabelNode doneLabel = new LabelNode();

        InsnList rewritten = new InsnList();
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rewritten.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, "keys", "Ljava/util/List;"));
        rewritten.add(new VarInsnNode(Opcodes.ASTORE, 2));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 2));
        rewritten.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        rewritten.add(new VarInsnNode(Opcodes.ISTORE, 3));
        rewritten.add(new InsnNode(Opcodes.ICONST_0));
        rewritten.add(new VarInsnNode(Opcodes.ISTORE, 4));
        rewritten.add(new InsnNode(Opcodes.ICONST_0));
        rewritten.add(new VarInsnNode(Opcodes.ISTORE, 5));

        rewritten.add(loopLabel);
        rewritten.add(new FrameNode(
                Opcodes.F_FULL,
                6,
                new Object[]{
                        TARGET_INTERNAL, "java/util/Map", "java/util/List",
                        Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER
                },
                0,
                null
        ));
        rewritten.add(new VarInsnNode(Opcodes.ILOAD, 5));
        rewritten.add(new VarInsnNode(Opcodes.ILOAD, 3));
        rewritten.add(new JumpInsnNode(Opcodes.IF_ICMPGE, doneLabel));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 2));
        rewritten.add(new VarInsnNode(Opcodes.ILOAD, 5));
        rewritten.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "get",
                "(I)Ljava/lang/Object;",
                true
        ));
        rewritten.add(new TypeInsnNode(Opcodes.CHECKCAST, FAST_MAP_KEY));
        rewritten.add(new VarInsnNode(Opcodes.ASTORE, 6));
        rewritten.add(new VarInsnNode(Opcodes.ILOAD, 4));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 6));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 6));
        rewritten.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                FAST_MAP_KEY,
                "getProperty",
                "()L" + PROPERTY_INTERNAL + ";",
                false
        ));
        rewritten.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true
        ));
        rewritten.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Comparable"));
        rewritten.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                FAST_MAP_KEY,
                partialMethodName,
                partialMethodDesc,
                false
        ));
        rewritten.add(new InsnNode(Opcodes.IADD));
        rewritten.add(new VarInsnNode(Opcodes.ISTORE, 4));
        rewritten.add(new IincInsnNode(5, 1));
        rewritten.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));

        rewritten.add(doneLabel);
        rewritten.add(new FrameNode(
                Opcodes.F_FULL,
                6,
                new Object[]{
                        TARGET_INTERNAL, "java/util/Map", "java/util/List",
                        Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER
                },
                0,
                null
        ));
        rewritten.add(new VarInsnNode(Opcodes.ILOAD, 4));
        rewritten.add(new InsnNode(Opcodes.IRETURN));

        method.instructions.clear();
        method.instructions.add(rewritten);
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks.clear();
        }
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = Math.max(method.maxStack, 4);
        method.maxLocals = Math.max(method.maxLocals, 7);
    }
}
