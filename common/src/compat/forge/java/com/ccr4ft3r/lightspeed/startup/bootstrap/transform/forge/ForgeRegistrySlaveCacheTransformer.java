package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Adds a lock-free two-entry identity cache for {@code ForgeRegistry.getSlaveMap} invalidated on
 * {@code ForgeRegistry.setSlaveMap}, avoiding repeated {@code HashMap.get(ResourceLocation)} lookups
 * across tens of thousands of registry callbacks.
 */
public final class ForgeRegistrySlaveCacheTransformer implements ITransformer<ClassNode> {
    private static final String TARGET_INTERNAL = "net/minecraftforge/registries/ForgeRegistry";
    private static final String ENTRY_INTERNAL = "java/util/Map$Entry";
    private static final String ENTRY_DESC = "Ljava/util/Map$Entry;";
    private static final String SIMPLE_ENTRY_INTERNAL = "java/util/AbstractMap$SimpleImmutableEntry";
    private static final String GET_SLAVE_DESC = "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Class;)Ljava/lang/Object;";
    private static final String SET_SLAVE_DESC = "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)V";
    private static final String SLAVE1_FIELD = "lightspeed$slave1";
    private static final String SLAVE2_FIELD = "lightspeed$slave2";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name) || hasSlaveField(input)) {
            return input;
        }
        MethodNode getSlaveMap = findMethod(input, "getSlaveMap", GET_SLAVE_DESC);
        MethodNode setSlaveMap = findMethod(input, "setSlaveMap", SET_SLAVE_DESC);
        if (getSlaveMap == null || setSlaveMap == null
                || !getSlaveMap.tryCatchBlocks.isEmpty()
                || !setSlaveMap.tryCatchBlocks.isEmpty()) {
            return input;
        }

        input.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_VOLATILE,
                SLAVE1_FIELD,
                ENTRY_DESC,
                null,
                null
        ));
        input.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_VOLATILE,
                SLAVE2_FIELD,
                ENTRY_DESC,
                null,
                null
        ));

        rewriteGetSlaveMap(getSlaveMap);
        instrumentSetSlaveMap(setSlaveMap);
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return Set.of(Target.targetClass("net.minecraftforge.registries.ForgeRegistry"));
    }

    private static boolean hasSlaveField(ClassNode input) {
        for (FieldNode field : input.fields) {
            if (SLAVE1_FIELD.equals(field.name)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode findMethod(ClassNode input, String name, String desc) {
        for (MethodNode method : input.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static void rewriteGetSlaveMap(MethodNode method) {
        InsnList insns = new InsnList();
        LabelNode checkSecond = new LabelNode();
        LabelNode fallback = new LabelNode();
        LabelNode writeFirst = new LabelNode();
        LabelNode retVal = new LabelNode();

        // Map.Entry s1 = this.lightspeed$slave1;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, SLAVE1_FIELD, ENTRY_DESC));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, checkSecond));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY_INTERNAL, "getKey", "()Ljava/lang/Object;", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IF_ACMPNE, checkSecond));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY_INTERNAL, "getValue", "()Ljava/lang/Object;", true));
        insns.add(new InsnNode(Opcodes.ARETURN));

        // Map.Entry s2 = this.lightspeed$slave2;
        insns.add(checkSecond);
        insns.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{ENTRY_INTERNAL}, 0, null));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, SLAVE2_FIELD, ENTRY_DESC));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY_INTERNAL, "getKey", "()Ljava/lang/Object;", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IF_ACMPNE, fallback));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY_INTERNAL, "getValue", "()Ljava/lang/Object;", true));
        insns.add(new InsnNode(Opcodes.ARETURN));

        // Object val = this.slaves.get(key);
        insns.add(fallback);
        insns.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, "slaves", "Ljava/util/Map;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, retVal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, retVal));

        // AbstractMap.SimpleImmutableEntry entry = new SimpleImmutableEntry(key, val);
        insns.add(new TypeInsnNode(Opcodes.NEW, SIMPLE_ENTRY_INTERNAL));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SIMPLE_ENTRY_INTERNAL, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 5));

        // Map.Entry prev = this.lightspeed$slave1; if (prev != null && prev.getKey() != key) slave2 = prev;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, SLAVE1_FIELD, ENTRY_DESC));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, writeFirst));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY_INTERNAL, "getKey", "()Ljava/lang/Object;", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, writeFirst));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, SLAVE2_FIELD, ENTRY_DESC));

        insns.add(writeFirst);
        insns.add(new FrameNode(
                Opcodes.F_APPEND,
                2,
                new Object[]{"java/lang/Object", SIMPLE_ENTRY_INTERNAL},
                0,
                null
        ));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, SLAVE1_FIELD, ENTRY_DESC));

        insns.add(retVal);
        insns.add(new FrameNode(
                Opcodes.F_FULL,
                5,
                new Object[]{
                        TARGET_INTERNAL,
                        "net/minecraft/resources/ResourceLocation",
                        "java/lang/Class",
                        ENTRY_INTERNAL,
                        "java/lang/Object"
                },
                0,
                new Object[0]
        ));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new InsnNode(Opcodes.ARETURN));

        method.instructions.clear();
        method.instructions.add(insns);
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = 4;
        method.maxLocals = 6;
    }

    private static void instrumentSetSlaveMap(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList clear = new InsnList();
                clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
                clear.add(new InsnNode(Opcodes.ACONST_NULL));
                clear.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, SLAVE1_FIELD, ENTRY_DESC));
                clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
                clear.add(new InsnNode(Opcodes.ACONST_NULL));
                clear.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, SLAVE2_FIELD, ENTRY_DESC));
                method.instructions.insertBefore(insn, clear);
            }
        }
        method.maxStack = Math.max(method.maxStack, 2);
    }
}
