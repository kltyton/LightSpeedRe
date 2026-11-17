package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft;

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
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Caches non-zero {@code ModelResourceLocation.hashCode()} values in a transient instance field to avoid
 * recomputing {@code 31 * super.hashCode() + variant.hashCode()} across model registry and bake lookups.
 */
public final class ModelResourceLocationHashTransformer implements ITransformer<ClassNode> {
    private static final String TARGET_INTERNAL = "net/minecraft/client/resources/model/ModelResourceLocation";
    private static final String RESOURCE_LOCATION_INTERNAL = "net/minecraft/resources/ResourceLocation";
    private static final String HASH_FIELD = "lightspeed$mrlHash";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name) || hasHashField(input)) {
            return input;
        }
        MethodNode hashCodeMethod = findHashCodeMethod(input);
        if (hashCodeMethod == null || (hashCodeMethod.tryCatchBlocks != null && !hashCodeMethod.tryCatchBlocks.isEmpty())) {
            return input;
        }
        String variantField = findVariantField(hashCodeMethod);
        if (variantField == null) {
            return input;
        }

        input.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT,
                HASH_FIELD,
                "I",
                null,
                null
        ));

        InsnList insns = new InsnList();
        LabelNode compute = new LabelNode();
        LabelNode ret = new LabelNode();

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, HASH_FIELD, "I"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, compute));
        insns.add(new InsnNode(Opcodes.IRETURN));

        insns.add(compute);
        insns.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{Opcodes.INTEGER}));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new IntInsnNode(Opcodes.BIPUSH, 31));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, RESOURCE_LOCATION_INTERNAL, "hashCode", "()I", false));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, variantField, "Ljava/lang/String;"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, ret));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, HASH_FIELD, "I"));

        insns.add(ret);
        insns.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{Opcodes.INTEGER}));
        insns.add(new InsnNode(Opcodes.IRETURN));

        hashCodeMethod.instructions.clear();
        hashCodeMethod.instructions.add(insns);
        if (hashCodeMethod.localVariables != null) {
            hashCodeMethod.localVariables.clear();
        }
        hashCodeMethod.maxStack = 3;
        hashCodeMethod.maxLocals = 1;
        return input;
    }

    private static boolean hasHashField(ClassNode input) {
        for (FieldNode field : input.fields) {
            if (HASH_FIELD.equals(field.name)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode findHashCodeMethod(ClassNode input) {
        for (MethodNode method : input.methods) {
            if ("hashCode".equals(method.name) && "()I".equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static String findVariantField(MethodNode hashCodeMethod) {
        String match = null;
        for (AbstractInsnNode insn = hashCodeMethod.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && TARGET_INTERNAL.equals(field.owner)
                    && "Ljava/lang/String;".equals(field.desc)) {
                if (match != null && !match.equals(field.name)) {
                    return null;
                }
                match = field.name;
            }
        }
        return match;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return Set.of(Target.targetClass("net.minecraft.client.resources.model.ModelResourceLocation"));
    }
}
