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

public final class EventBusAsmLoaderPatch {
    private static final String DEFINE_DESCRIPTOR = "(Ljava/lang/String;[B)Ljava/lang/Class;";
    private static final String DEFINE_CLASS_DESCRIPTOR = "(Ljava/lang/String;[BII)Ljava/lang/Class;";

    private EventBusAsmLoaderPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode define = AsmPatchSupport.method(node, "define", DEFINE_DESCRIPTOR);
        AsmPatchSupport.uniqueInvocation(define, Opcodes.INVOKEVIRTUAL, node.name,
                "defineClass", DEFINE_CLASS_DESCRIPTOR);
        LabelNode notLoaded = new LabelNode();
        InsnList reuse = new InsnList();
        reuse.add(new VarInsnNode(Opcodes.ALOAD, 0));
        reuse.add(new VarInsnNode(Opcodes.ALOAD, 1));
        reuse.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "findLoadedClass",
                "(Ljava/lang/String;)Ljava/lang/Class;", false));
        reuse.add(new InsnNode(Opcodes.DUP));
        reuse.add(new JumpInsnNode(Opcodes.IFNULL, notLoaded));
        reuse.add(new InsnNode(Opcodes.ARETURN));
        reuse.add(notLoaded);
        reuse.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Class"}));
        reuse.add(new InsnNode(Opcodes.POP));
        define.instructions.insertBefore(define.instructions.getFirst(), reuse);
        return AsmPatchSupport.write(node);
    }
}
