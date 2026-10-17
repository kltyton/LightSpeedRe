package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ListenerListGrowthPatch {
    private ListenerListGrowthPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode resize = AsmPatchSupport.method(node, "resize", "(I)V");
        InsnList capacity = new InsnList();
        capacity.add(new VarInsnNode(Opcodes.ILOAD, 0));
        capacity.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "eventBusCapacity", "(I)I", false));
        capacity.add(new VarInsnNode(Opcodes.ISTORE, 0));
        resize.instructions.insertBefore(resize.instructions.getFirst(), capacity);
        return AsmPatchSupport.write(node);
    }
}
