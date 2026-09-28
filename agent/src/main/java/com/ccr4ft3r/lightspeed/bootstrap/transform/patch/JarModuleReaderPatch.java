package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class JarModuleReaderPatch {
    private JarModuleReaderPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode open = AsmPatchSupport.method(node, "open", "(Ljava/lang/String;)Ljava/util/Optional;");
        MethodInsnNode findFile = AsmPatchSupport.uniqueInvocation(open, Opcodes.INVOKEVIRTUAL,
                "cpw/mods/jarhandling/impl/Jar", "findFile", "(Ljava/lang/String;)Ljava/util/Optional;");
        AbstractInsnNode mapper = findFile.getNext();
        while (mapper.getOpcode() < 0) mapper = mapper.getNext();
        if (!(mapper instanceof InvokeDynamicInsnNode)) {
            throw new IllegalStateException("Unexpected module URI mapper");
        }
        AbstractInsnNode convert = mapper.getNext();
        while (convert.getOpcode() < 0) convert = convert.getNext();
        if (!(convert instanceof MethodInsnNode call)
                || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !call.owner.equals("java/util/Optional")
                || !call.name.equals("map")
                || !call.desc.equals("(Ljava/util/function/Function;)Ljava/util/Optional;")) {
            throw new IllegalStateException("Unexpected module URI conversion");
        }
        findFile.name = "lightspeed$findPath";
        open.instructions.remove(mapper);
        open.instructions.remove(convert);
        return AsmPatchSupport.write(node);
    }
}
