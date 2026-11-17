package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Memoizes {@code org.objectweb.asm.Type#hashCode()} in an instance field because ASM recomputes
 * the descriptor hash loop on every lookup while Forge/NeoForge scan metadata records hash heavily.
 */
public final class TypeHashPatch {
    private static final String CACHE_FIELD = "lightspeed$cachedHash";

    private TypeHashPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        boolean hasField = node.fields.stream().anyMatch(f -> CACHE_FIELD.equals(f.name));
        if (!hasField) {
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, CACHE_FIELD, "I", null, null));
        }

        MethodNode hashCode = AsmPatchSupport.method(node, "hashCode", "()I");
        AbstractInsnNode firstOriginal = hashCode.instructions.getFirst();
        if (firstOriginal == null) {
            return bytes;
        }

        java.util.List<AbstractInsnNode> returns = new java.util.ArrayList<>(2);
        for (AbstractInsnNode insn : hashCode.instructions) {
            if (insn.getOpcode() == Opcodes.IRETURN) {
                returns.add(insn);
            }
        }
        if (returns.isEmpty()) {
            return bytes;
        }

        for (AbstractInsnNode ret : returns) {
            InsnList store = new InsnList();
            store.add(new InsnNode(Opcodes.DUP));
            store.add(new VarInsnNode(Opcodes.ALOAD, 0));
            store.add(new InsnNode(Opcodes.SWAP));
            store.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, CACHE_FIELD, "I"));
            hashCode.instructions.insertBefore(ret, store);
        }

        LabelNode compute = new LabelNode();
        InsnList fastPath = new InsnList();
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, CACHE_FIELD, "I"));
        fastPath.add(new InsnNode(Opcodes.DUP));
        fastPath.add(new JumpInsnNode(Opcodes.IFEQ, compute));
        fastPath.add(new InsnNode(Opcodes.IRETURN));
        fastPath.add(compute);
        fastPath.add(new InsnNode(Opcodes.POP));
        hashCode.instructions.insertBefore(firstOriginal, fastPath);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }
}
