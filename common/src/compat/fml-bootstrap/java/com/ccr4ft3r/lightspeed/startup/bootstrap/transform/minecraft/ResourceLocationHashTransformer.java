package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.CompatClassTransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
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

/** Caches ResourceLocation.hashCode() directly on the instance without Mixin callback overhead. */
public final class ResourceLocationHashTransformer extends CompatClassTransformer {
    private static final String TARGET = "net.minecraft.resources.ResourceLocation";
    private static final String TARGET_INTERNAL = "net/minecraft/resources/ResourceLocation";
    private static final String CACHED_HASH_FIELD = "lightspeed$cachedHash";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        MethodNode hashCodeMethod = findHashCodeMethod(input);
        if (hashCodeMethod != null) {
            String[] stringFields = extractTwoStringFields(hashCodeMethod);
            if (stringFields != null) {
                ensureCachedHashField(input);
                rewriteHashCode(hashCodeMethod, stringFields[0], stringFields[1]);
            }
        }
        rewriteValidationMethods(input);
        return input;
    }

    @Override
    protected Set<String> targetClassNames() {
        return Set.of(TARGET);
    }

    private static MethodNode findHashCodeMethod(ClassNode input) {
        for (MethodNode method : input.methods) {
            if ("hashCode".equals(method.name) && "()I".equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static String[] extractTwoStringFields(MethodNode hashCodeMethod) {
        String first = null;
        String second = null;
        for (AbstractInsnNode insn = hashCodeMethod.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GETFIELD
                    && insn instanceof FieldInsnNode field
                    && TARGET_INTERNAL.equals(field.owner)
                    && "Ljava/lang/String;".equals(field.desc)) {
                if (first == null) {
                    first = field.name;
                } else if (second == null) {
                    second = field.name;
                } else {
                    return null;
                }
            }
        }
        return first != null && second != null ? new String[]{first, second} : null;
    }

    private static void ensureCachedHashField(ClassNode input) {
        for (FieldNode field : input.fields) {
            if (CACHED_HASH_FIELD.equals(field.name) && "I".equals(field.desc)) {
                return;
            }
        }
        input.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, CACHED_HASH_FIELD, "I", null, null));
    }

    private static void rewriteHashCode(MethodNode hashCodeMethod, String firstField, String secondField) {
        LabelNode computeLabel = new LabelNode();
        LabelNode returnZeroLabel = new LabelNode();

        InsnList rewritten = new InsnList();
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rewritten.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, CACHED_HASH_FIELD, "I"));
        rewritten.add(new InsnNode(Opcodes.DUP));
        rewritten.add(new JumpInsnNode(Opcodes.IFEQ, computeLabel));
        rewritten.add(new InsnNode(Opcodes.IRETURN));

        rewritten.add(computeLabel);
        rewritten.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{Opcodes.INTEGER}));
        rewritten.add(new InsnNode(Opcodes.POP));
        rewritten.add(new IntInsnNode(Opcodes.BIPUSH, 31));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rewritten.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, firstField, "Ljava/lang/String;"));
        rewritten.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
        rewritten.add(new InsnNode(Opcodes.IMUL));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rewritten.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, secondField, "Ljava/lang/String;"));
        rewritten.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
        rewritten.add(new InsnNode(Opcodes.IADD));
        rewritten.add(new InsnNode(Opcodes.DUP));
        rewritten.add(new JumpInsnNode(Opcodes.IFEQ, returnZeroLabel));
        rewritten.add(new InsnNode(Opcodes.DUP));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rewritten.add(new InsnNode(Opcodes.SWAP));
        rewritten.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, CACHED_HASH_FIELD, "I"));

        rewritten.add(returnZeroLabel);
        rewritten.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{Opcodes.INTEGER}));
        rewritten.add(new InsnNode(Opcodes.IRETURN));

        hashCodeMethod.instructions.clear();
        hashCodeMethod.instructions.add(rewritten);
        if (hashCodeMethod.tryCatchBlocks != null) {
            hashCodeMethod.tryCatchBlocks.clear();
        }
        if (hashCodeMethod.localVariables != null) {
            hashCodeMethod.localVariables.clear();
        }
        hashCodeMethod.maxStack = Math.max(hashCodeMethod.maxStack, 3);
        hashCodeMethod.maxLocals = Math.max(hashCodeMethod.maxLocals, 1);
    }

    private static void rewriteValidationMethods(ClassNode input) {
        for (MethodNode method : input.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }
            if (("m_135828_".equals(method.name) || "validPathChar".equals(method.name))
                    && "(C)Z".equals(method.desc)) {
                rewriteCharLookup(method, "PATH_CHARS");
            } else if (("m_135835_".equals(method.name) || "validNamespaceChar".equals(method.name))
                    && "(C)Z".equals(method.desc)) {
                rewriteCharLookup(method, "NAMESPACE_CHARS");
            }
        }
    }

    private static void rewriteCharLookup(MethodNode method, String tableField) {
        InsnList rewritten = new InsnList();
        LabelNode outOfRangeLabel = new LabelNode();
        rewritten.add(new VarInsnNode(Opcodes.ILOAD, 0));
        rewritten.add(new IntInsnNode(Opcodes.BIPUSH, 127));
        rewritten.add(new JumpInsnNode(Opcodes.IF_ICMPGT, outOfRangeLabel));
        rewritten.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/ccr4ft3r/lightspeed/startup/compat/ResourceLocationLookupSupport",
                tableField,
                "[Z"
        ));
        rewritten.add(new VarInsnNode(Opcodes.ILOAD, 0));
        rewritten.add(new InsnNode(Opcodes.BALOAD));
        rewritten.add(new InsnNode(Opcodes.IRETURN));
        rewritten.add(outOfRangeLabel);
        rewritten.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        rewritten.add(new InsnNode(Opcodes.ICONST_0));
        rewritten.add(new InsnNode(Opcodes.IRETURN));

        method.instructions.clear();
        method.instructions.add(rewritten);
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks.clear();
        }
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = Math.max(method.maxStack, 2);
        method.maxLocals = Math.max(method.maxLocals, 1);
    }

}
