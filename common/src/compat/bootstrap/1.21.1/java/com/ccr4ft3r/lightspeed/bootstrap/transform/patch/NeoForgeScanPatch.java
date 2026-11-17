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

/** Replays complete scan metadata after NeoForge has registered the owning mod file. */
public final class NeoForgeScanPatch {
    private static final String DATA = "net/neoforged/neoforgespi/language/ModFileScanData";
    private static final String MOD_FILE = "net/neoforged/fml/loading/moddiscovery/ModFile";

    private NeoForgeScanPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode scan = AsmPatchSupport.method(node, "scan", "()L" + DATA + ";");
        MethodInsnNode scanFile = AsmPatchSupport.uniqueInvocation(scan, Opcodes.INVOKEVIRTUAL,
                MOD_FILE, "scanFile", "(Ljava/util/function/Consumer;)V");
        AbstractInsnNode start = scanFile;
        while (start != null && !(start instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(node.name)
                && field.name.equals("fileToScan"))) {
            start = start.getPrevious();
        }
        if (start == null || !(start.getPrevious() instanceof VarInsnNode receiver)
                || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0) {
            throw new IllegalStateException("missing NeoForge Scanner.scan file receiver");
        }
        for (AbstractInsnNode instruction : scan.instructions) {
            if (instruction instanceof FrameNode) {
                throw new IllegalStateException("unexpected NeoForge Scanner.scan frame");
            }
        }

        LabelNode miss = new LabelNode();
        LabelNode complete = new LabelNode();
        InsnList replay = new InsnList();
        replay.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replay.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "fileToScan", "L" + MOD_FILE + ";"));
        replay.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replay.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "replayScanMetadata", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        replay.add(new JumpInsnNode(Opcodes.IFEQ, miss));
        replay.add(new JumpInsnNode(Opcodes.GOTO, complete));
        replay.add(miss);
        replay.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{DATA}, 0, null));
        scan.instructions.insertBefore(receiver, replay);

        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ALOAD, 0));
        record.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "fileToScan", "L" + MOD_FILE + ";"));
        record.add(new VarInsnNode(Opcodes.ALOAD, 1));
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "recordScanMetadata", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        record.add(complete);
        record.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        scan.instructions.insert(scanFile, record);
        return AsmPatchSupport.write(node);
    }
}
