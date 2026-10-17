package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class EventWrapperPatch {
    private static final String DESCRIPTOR = "(Ljava/lang/reflect/Method;)Ljava/lang/Class;";

    private EventWrapperPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "createWrapper", DESCRIPTOR);
        LabelNode original = new LabelNode();
        InsnList fastPath = new InsnList();
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastPath.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "canUseDirectEventWrapper", "(Ljava/lang/reflect/Method;)Z", false));
        fastPath.add(new JumpInsnNode(Opcodes.IFEQ, original));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastPath.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "createWrapper", DESCRIPTOR, false));
        fastPath.add(new InsnNode(Opcodes.ARETURN));
        fastPath.add(original);
        fastPath.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insertBefore(method.instructions.getFirst(), fastPath);
        return AsmPatchSupport.write(node);
    }
}
