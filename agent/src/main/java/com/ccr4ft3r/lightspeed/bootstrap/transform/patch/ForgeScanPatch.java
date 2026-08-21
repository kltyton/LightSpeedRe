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

public final class ForgeScanPatch {
    private static final String METHOD_DESCRIPTOR =
            "(Ljava/nio/file/Path;Lnet/minecraftforge/forgespi/language/ModFileScanData;)V";

    private ForgeScanPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "fileVisitor", METHOD_DESCRIPTOR);
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                returns.add(instruction);
            }
        }
        if (returns.size() != 1) {
            throw new IllegalStateException("unexpected Scanner.fileVisitor return count " + returns.size());
        }

        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ALOAD, 1));
        record.add(new VarInsnNode(Opcodes.ALOAD, 2));
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "recordScanMetadata",
                "(Ljava/nio/file/Path;Ljava/lang/Object;)V", false));
        method.instructions.insertBefore(returns.get(0), record);

        LabelNode miss = new LabelNode();
        InsnList lookup = new InsnList();
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 1));
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 2));
        lookup.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "replayScanMetadata",
                "(Ljava/nio/file/Path;Ljava/lang/Object;)Z", false));
        lookup.add(new JumpInsnNode(Opcodes.IFEQ, miss));
        lookup.add(new InsnNode(Opcodes.RETURN));
        lookup.add(miss);
        lookup.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insertBefore(method.instructions.getFirst(), lookup);
        return AsmPatchSupport.write(node);
    }
}
