package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class BackgroundScanPatch {
    private static final String MOD_FILE = "net/minecraftforge/fml/loading/moddiscovery/ModFile";
    private static final String SCAN_DATA = "net/minecraftforge/forgespi/language/ModFileScanData";

    private BackgroundScanPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode constructor = AsmPatchSupport.method(node, "<init>", "(Ljava/util/List;)V");
        MethodInsnNode executorFactory = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof MethodInsnNode method
                    && method.getOpcode() == Opcodes.INVOKESTATIC
                    && method.owner.equals("java/util/concurrent/Executors")
                    && method.name.equals("newSingleThreadExecutor")
                    && method.desc.equals("(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;")) {
                if (executorFactory != null) {
                    throw new IllegalStateException("duplicate Mod scan executor factory");
                }
                executorFactory = method;
            }
        }
        if (executorFactory == null) {
            throw new IllegalStateException("missing Mod scan executor factory");
        }
        constructor.instructions.set(executorFactory, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                AsmPatchSupport.HOOK_OWNER,
                "createModScanExecutor",
                "(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;",
                false));

        synchronize(AsmPatchSupport.method(node, "submitForScanning", "(L" + MOD_FILE + ";)V"));
        synchronize(AsmPatchSupport.method(node, "addCompletedFile",
                "(L" + MOD_FILE + ";L" + SCAN_DATA + ";Ljava/lang/Throwable;)V"));
        return AsmPatchSupport.write(node);
    }

    private static void synchronize(MethodNode method) {
        method.access |= Opcodes.ACC_SYNCHRONIZED;
    }
}
