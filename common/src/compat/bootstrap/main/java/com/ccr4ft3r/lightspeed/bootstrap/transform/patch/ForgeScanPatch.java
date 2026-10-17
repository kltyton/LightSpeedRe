package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;

public final class ForgeScanPatch {
    private static final String SCAN_DESCRIPTOR =
            "()Lnet/minecraftforge/forgespi/language/ModFileScanData;";
    private static final String MOD_FILE = "net/minecraftforge/fml/loading/moddiscovery/ModFile";

    private ForgeScanPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "scan", SCAN_DESCRIPTOR);
        FrameNode firstOriginalFrame = firstFrame(method);
        MethodInsnNode scanFile = AsmPatchSupport.uniqueInvocation(method, Opcodes.INVOKEVIRTUAL,
                MOD_FILE, "scanFile", "(Ljava/util/function/Consumer;)V");
        AbstractInsnNode invocationStart = invocationStart(node, scanFile);

        LabelNode miss = new LabelNode();
        LabelNode afterScan = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "fileToScan", 'L' + MOD_FILE + ';'));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "replayScanMetadata",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, miss));
        guard.add(new JumpInsnNode(Opcodes.GOTO, afterScan));
        guard.add(miss);
        guard.add(new FrameNode(Opcodes.F_APPEND, 1,
                new Object[]{"net/minecraftforge/forgespi/language/ModFileScanData"}, 0, null));
        method.instructions.insertBefore(invocationStart, guard);

        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ALOAD, 0));
        record.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "fileToScan", 'L' + MOD_FILE + ';'));
        record.add(new VarInsnNode(Opcodes.ALOAD, 1));
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "recordScanMetadata",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        record.add(afterScan);
        record.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(scanFile, record);
        if (firstOriginalFrame.type != Opcodes.F_APPEND || firstOriginalFrame.local == null
                || firstOriginalFrame.local.size() != 2) {
            throw new IllegalStateException("unexpected Scanner.scan first frame");
        }
        firstOriginalFrame.local = new ArrayList<>(firstOriginalFrame.local.subList(1, 2));
        return AsmPatchSupport.write(node);
    }

    private static FrameNode firstFrame(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FrameNode frame) {
                return frame;
            }
        }
        throw new IllegalStateException("missing Scanner.scan frame");
    }

    private static AbstractInsnNode invocationStart(ClassNode node, MethodInsnNode scanFile) {
        for (AbstractInsnNode instruction = scanFile.getPrevious(); instruction != null;
             instruction = instruction.getPrevious()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(node.name)
                    && field.name.equals("fileToScan")) {
                AbstractInsnNode receiver = field.getPrevious();
                if (receiver instanceof VarInsnNode variable
                        && variable.getOpcode() == Opcodes.ALOAD
                        && variable.var == 0) {
                    return receiver;
                }
                break;
            }
        }
        throw new IllegalStateException("missing Scanner.scan fileToScan invocation start");
    }
}
