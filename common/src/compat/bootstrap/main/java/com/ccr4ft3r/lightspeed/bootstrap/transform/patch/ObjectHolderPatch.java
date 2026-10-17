package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.ArrayList;
import java.util.List;

public final class ObjectHolderPatch {
    private static final String APPLY_DESCRIPTOR = "(Ljava/util/function/Predicate;)V";

    private ObjectHolderPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        var node = AsmPatchSupport.read(bytes);
        MethodNode apply = AsmPatchSupport.method(node, "applyObjectHolders", APPLY_DESCRIPTOR);
        apply.instructions.clear();
        apply.tryCatchBlocks.clear();
        apply.localVariables = null;
        apply.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, "objectHolders", "Ljava/util/Set;"));
        apply.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        apply.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "applyObjectHolders", "(Ljava/util/Set;Ljava/util/function/Predicate;)V", false));
        apply.instructions.add(new InsnNode(Opcodes.RETURN));

        invalidateOnReturn(AsmPatchSupport.method(node, "addHandler", "(Ljava/util/function/Consumer;)V"));
        invalidateOnReturn(AsmPatchSupport.method(node, "removeHandler", "(Ljava/util/function/Consumer;)Z"));
        return AsmPatchSupport.write(node);
    }

    private static void invalidateOnReturn(MethodNode method) {
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN || instruction.getOpcode() == Opcodes.IRETURN) {
                returns.add(instruction);
            }
        }
        if (returns.isEmpty()) {
            throw new IllegalStateException("ObjectHolder mutation method has no return");
        }
        for (AbstractInsnNode instruction : returns) {
            InsnList invalidate = new InsnList();
            invalidate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                    "objectHoldersChanged", "()V", false));
            method.instructions.insertBefore(instruction, invalidate);
        }
    }
}
