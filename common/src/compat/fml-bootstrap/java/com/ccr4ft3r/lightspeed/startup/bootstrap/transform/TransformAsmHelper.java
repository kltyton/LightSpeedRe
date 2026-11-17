package com.ccr4ft3r.lightspeed.startup.bootstrap.transform;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.HashSet;
import java.util.Set;

/** Shared ASM inspection helpers used by {@code Layer.SERVICE} class transformers. */
public final class TransformAsmHelper {
    private TransformAsmHelper() {
    }

    public static Set<LabelNode> collectControlFlowLabels(MethodNode method) {
        Set<LabelNode> labels = new HashSet<>();
        for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
            labels.add(tcb.start);
            labels.add(tcb.end);
            labels.add(tcb.handler);
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode jump) {
                labels.add(jump.label);
            } else if (insn instanceof TableSwitchInsnNode ts) {
                labels.add(ts.dflt);
                labels.addAll(ts.labels);
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                labels.add(ls.dflt);
                labels.addAll(ls.labels);
            }
        }
        return labels;
    }

    public static AbstractInsnNode nextRealInsn(AbstractInsnNode node) {
        AbstractInsnNode next = node.getNext();
        while (next != null && next.getOpcode() == -1) {
            next = next.getNext();
        }
        return next;
    }

    public static AbstractInsnNode previousRealInsn(AbstractInsnNode node) {
        AbstractInsnNode prev = node.getPrevious();
        while (prev != null && prev.getOpcode() == -1) {
            prev = prev.getPrevious();
        }
        return prev;
    }
}
