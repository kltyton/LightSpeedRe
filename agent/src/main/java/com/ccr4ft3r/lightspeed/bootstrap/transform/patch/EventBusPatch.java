package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class EventBusPatch {
    private EventBusPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "getDeclMethod",
                "(Ljava/lang/Class;Ljava/lang/reflect/Method;)Ljava/util/Optional;");
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "declaredEventMethod",
                "(Ljava/lang/Class;Ljava/lang/reflect/Method;)Ljava/util/Optional;", false));
        replacement.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(replacement);
        return AsmPatchSupport.write(node);
    }
}
