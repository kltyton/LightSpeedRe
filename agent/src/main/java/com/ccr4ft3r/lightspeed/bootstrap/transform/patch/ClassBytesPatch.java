package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

public final class ClassBytesPatch {
    private static final String METHOD_DESCRIPTOR =
            "(Ljava/lang/module/ModuleReader;Ljava/lang/module/ModuleReference;Ljava/lang/String;)[B";

    private ClassBytesPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "getClassBytes", METHOD_DESCRIPTOR);
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                returns.add(instruction);
            }
        }
        if (returns.size() != 1) {
            throw new IllegalStateException("unexpected getClassBytes return count " + returns.size());
        }

        int resultLocal = method.maxLocals++;
        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
        record.add(new VarInsnNode(Opcodes.ALOAD, 2));
        record.add(new VarInsnNode(Opcodes.ALOAD, 3));
        record.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "recordRawClassBytes",
                "(Ljava/lang/module/ModuleReference;Ljava/lang/String;[B)V", false));
        record.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        method.instructions.insertBefore(returns.get(0), record);

        LabelNode miss = new LabelNode();
        InsnList lookup = new InsnList();
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 2));
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 3));
        lookup.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "rawClassBytes",
                "(Ljava/lang/module/ModuleReference;Ljava/lang/String;)[B", false));
        lookup.add(new InsnNode(Opcodes.DUP));
        lookup.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        lookup.add(new InsnNode(Opcodes.ARETURN));
        lookup.add(miss);
        lookup.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"[B"}));
        lookup.add(new InsnNode(Opcodes.POP));
        method.instructions.insertBefore(method.instructions.getFirst(), lookup);
        return AsmPatchSupport.write(node);
    }
}
