package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Disables hardcoded VSync during FML EarlyDisplay startup so buffer swaps never stall main-thread loading. */
public final class EarlyDisplayVsyncPatch {
    private EarlyDisplayVsyncPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        var node = AsmPatchSupport.read(bytes);
        boolean changed = false;
        for (var method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn.getOpcode() == Opcodes.ICONST_1
                        && next instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && "org/lwjgl/glfw/GLFW".equals(call.owner)
                        && "glfwSwapInterval".equals(call.name)
                        && "(I)V".equals(call.desc)) {
                    method.instructions.insertBefore(insn, new InsnNode(Opcodes.ICONST_0));
                    method.instructions.remove(insn);
                    changed = true;
                }
                insn = next;
            }
        }
        return changed ? AsmPatchSupport.write(node) : bytes;
    }
}
