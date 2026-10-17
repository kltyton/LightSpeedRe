package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class AsmPatchSupport {
    static final String HOOK_OWNER = "com/ccr4ft3r/lightspeed/bootstrap/runtime/BootstrapHooks";

    private AsmPatchSupport() {
    }

    static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing method " + name + descriptor));
    }

    static MethodInsnNode uniqueInvocation(MethodNode method, int opcode, String owner, String name, String descriptor) {
        MethodInsnNode match = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.getOpcode() == opcode
                    && invocation.owner.equals(owner)
                    && invocation.name.equals(name)
                    && invocation.desc.equals(descriptor)) {
                if (match != null) {
                    throw new IllegalStateException("duplicate target invocation " + owner + '.' + name + descriptor);
                }
                match = invocation;
            }
        }
        if (match == null) {
            throw new IllegalStateException("missing target invocation " + owner + '.' + name + descriptor);
        }
        return match;
    }

    static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
