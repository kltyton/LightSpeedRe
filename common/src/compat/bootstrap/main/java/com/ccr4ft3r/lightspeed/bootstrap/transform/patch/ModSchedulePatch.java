package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Enables FIFO scheduling (asyncMode = true) on ModWorkManager's ForkJoinPool for both
 * Forge 1.20.1 and NeoForge 1.21.1 while preserving native ModList topological submission order.
 */
public final class ModSchedulePatch {
    private static final String FJP_INIT_DESC =
            "(ILjava/util/concurrent/ForkJoinPool$ForkJoinWorkerThreadFactory;"
                    + "Ljava/lang/Thread$UncaughtExceptionHandler;Z)V";

    private ModSchedulePatch() {
    }

    public static byte[] workManagerFifo(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "parallelExecutor", "()Ljava/util/concurrent/Executor;");
        MethodInsnNode init = AsmPatchSupport.uniqueInvocation(method, Opcodes.INVOKESPECIAL,
                "java/util/concurrent/ForkJoinPool", "<init>", FJP_INIT_DESC);
        AbstractInsnNode asyncFlag = previousReal(init);
        if (asyncFlag == null || asyncFlag.getOpcode() != Opcodes.ICONST_0) {
            throw new IllegalStateException("expected ICONST_0 asyncMode before ForkJoinPool.<init>");
        }
        method.instructions.insertBefore(asyncFlag, new InsnNode(Opcodes.ICONST_1));
        method.instructions.remove(asyncFlag);
        return AsmPatchSupport.write(node);
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode current = insn == null ? null : insn.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }
}
