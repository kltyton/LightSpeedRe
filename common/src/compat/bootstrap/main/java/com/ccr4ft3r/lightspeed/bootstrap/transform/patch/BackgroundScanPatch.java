package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
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

        synchronize(node.name, AsmPatchSupport.method(node, "submitForScanning", "(L" + MOD_FILE + ";)V"));
        synchronize(node.name, AsmPatchSupport.method(node, "addCompletedFile",
                "(L" + MOD_FILE + ";L" + SCAN_DATA + ";Ljava/lang/Throwable;)V"));
        return AsmPatchSupport.write(node);
    }

    private static void synchronize(String owner, MethodNode method) {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode exit = new LabelNode();
        InsnList acquire = new InsnList();
        acquire.add(new VarInsnNode(Opcodes.ALOAD, 0));
        acquire.add(new InsnNode(Opcodes.MONITORENTER));
        acquire.add(start);
        method.instructions.insert(acquire);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, exit));
            }
        }
        Object[] locals = new Object[Type.getArgumentTypes(method.desc).length + 1];
        locals[0] = owner;
        for (int i = 1; i < locals.length; i++) {
            locals[i] = Type.getArgumentTypes(method.desc)[i - 1].getInternalName();
        }
        method.instructions.add(end);
        method.instructions.add(exit);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 0, null));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.MONITOREXIT));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, locals.length, locals,
                1, new Object[]{"java/lang/Throwable"}));
        int failure = method.maxLocals++;
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, failure));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.MONITOREXIT));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, failure));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    }
}
