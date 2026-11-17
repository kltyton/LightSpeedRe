package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Registers existing and new Union 3 roots when their native path factory is used. */
public final class UnionFileSystemRootPatch {
    private static final String FILTER = "cpw/mods/niofs/union/UnionPathFilter";
    private static final String BINDING = "com/ccr4ft3r/lightspeed/bootstrap/runtime/index/UnionRootBinding";
    private static final String TEST = BINDING + "$PathFilterTest";
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    private UnionFileSystemRootPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode getPath = AsmPatchSupport.method(node, "getPath",
                "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;");
        InsnList registration = new InsnList();
        registration.add(new VarInsnNode(Opcodes.ALOAD, 0));
        registration.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getRoot",
                "()Ljava/nio/file/Path;", false));
        registration.add(new VarInsnNode(Opcodes.ALOAD, 0));
        registration.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "basepaths", "Ljava/util/List;"));
        registration.add(new VarInsnNode(Opcodes.ALOAD, 0));
        registration.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "pathFilter", "L" + FILTER + ";"));
        registration.add(new InvokeDynamicInsnNode("test", "()L" + TEST + ";", METAFACTORY,
                Type.getMethodType("(Ljava/lang/Object;Ljava/lang/String;Ljava/nio/file/Path;)Z"),
                new Handle(Opcodes.H_INVOKEINTERFACE, FILTER, "test",
                        "(Ljava/lang/String;Ljava/nio/file/Path;)Z", true),
                Type.getMethodType("(L" + FILTER + ";Ljava/lang/String;Ljava/nio/file/Path;)Z")));
        registration.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BINDING, "register",
                "(Ljava/nio/file/Path;Ljava/util/List;Ljava/lang/Object;L" + TEST + ";)V", false));
        getPath.instructions.insertBefore(getPath.instructions.getFirst(), registration);
        return AsmPatchSupport.write(node);
    }
}
