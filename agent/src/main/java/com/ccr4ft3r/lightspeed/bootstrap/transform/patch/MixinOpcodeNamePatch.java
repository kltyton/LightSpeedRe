package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
import org.objectweb.asm.tree.VarInsnNode;

public final class MixinOpcodeNamePatch {
    private MixinOpcodeNamePatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "getOpcodeName", "(I)Ljava/lang/String;");
        AsmPatchSupport.uniqueInvocation(method, Opcodes.INVOKESTATIC, node.name,
                "getOpcodeName", "(ILjava/lang/String;I)Ljava/lang/String;");
        MethodNode lookup = AsmPatchSupport.method(node, "getOpcodeName",
                "(ILjava/lang/String;I)Ljava/lang/String;");
        Type constants = null;
        for (AbstractInsnNode instruction : lookup.instructions) {
            if (instruction instanceof LdcInsnNode literal && literal.cst instanceof Type type) {
                if (constants != null) {
                    throw new IllegalStateException("multiple opcode constants classes");
                }
                constants = type;
            }
        }
        if (constants == null) {
            throw new IllegalStateException("missing opcode constants class");
        }
        LabelNode original = new LabelNode();
        InsnList cached = new InsnList();
        cached.add(new VarInsnNode(Opcodes.ILOAD, 0));
        cached.add(new JumpInsnNode(Opcodes.IFLT, original));
        cached.add(new VarInsnNode(Opcodes.ILOAD, 0));
        cached.add(new IntInsnNode(Opcodes.SIPUSH, 256));
        cached.add(new JumpInsnNode(Opcodes.IF_ICMPGE, original));
        cached.add(new LdcInsnNode(constants));
        cached.add(new VarInsnNode(Opcodes.ILOAD, 0));
        cached.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "opcodeName", "(Ljava/lang/Class;I)Ljava/lang/String;", false));
        cached.add(new InsnNode(Opcodes.ARETURN));
        cached.add(original);
        cached.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(cached);
        return AsmPatchSupport.write(node);
    }
}
