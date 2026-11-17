package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Restores scoped dispatch when a runtime transformer replaces the Forge Mixin entry. */
public final class ObjectHolderInteropPatch {
    private ObjectHolderInteropPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        var method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("applyIndexedObjectHolders")
                        && candidate.desc.equals("(Ljava/util/Set;Ljava/util/function/Predicate;)V"))
                .findFirst().orElseThrow();
        LabelNode unscoped = new LabelNode();
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/ccr4ft3r/lightspeed/bootstrap/runtime/BootstrapHooks", "tryDispatchObjectHolders",
                "(Ljava/util/Set;Ljava/util/function/Predicate;)Z", false));
        entry.add(new JumpInsnNode(Opcodes.IFEQ, unscoped));
        entry.add(new InsnNode(Opcodes.RETURN));
        entry.add(unscoped);
        entry.add(new FrameNode(Opcodes.F_NEW, 2,
                new Object[]{"java/util/Set", "java/util/function/Predicate"}, 0, null));
        method.instructions.insert(entry);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
