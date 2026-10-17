package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnNode;

public final class UnionInputStreamPatch {
    private UnionInputStreamPatch() {
    }

    public static byte[] fileSystem(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        AsmPatchSupport.method(node, "findFirstFiltered",
                "(Lcpw/mods/niofs/union/UnionPath;)Ljava/util/Optional;");
        MethodNode open = new MethodNode(Opcodes.ACC_PUBLIC, "lightspeed$openInput",
                "(Lcpw/mods/niofs/union/UnionPath;)Ljava/io/InputStream;", null,
                new String[]{"java/io/IOException"});
        open.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        open.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        open.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, "findFirstFiltered",
                "(Lcpw/mods/niofs/union/UnionPath;)Ljava/util/Optional;", false));
        open.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        open.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        open.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/nio/file/Path"));
        open.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "openUnionInput", "(Ljava/nio/file/Path;)Ljava/io/InputStream;", false));
        open.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(open);
        return AsmPatchSupport.write(node);
    }

    public static byte[] provider(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        AsmPatchSupport.method(node, "newByteChannel",
                "(Ljava/nio/file/Path;Ljava/util/Set;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/channels/SeekableByteChannel;");
        MethodNode open = new MethodNode(Opcodes.ACC_PUBLIC, "newInputStream",
                "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;", null,
                new String[]{"java/io/IOException"});
        open.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        open.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "validateUnionInputOptions", "([Ljava/nio/file/OpenOption;)V", false));
        open.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        open.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "cpw/mods/niofs/union/UnionPath"));
        open.instructions.add(new InsnNode(Opcodes.DUP));
        open.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionPath",
                "getFileSystem", "()Lcpw/mods/niofs/union/UnionFileSystem;", false));
        open.instructions.add(new InsnNode(Opcodes.SWAP));
        open.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionFileSystem",
                "lightspeed$openInput", "(Lcpw/mods/niofs/union/UnionPath;)Ljava/io/InputStream;", false));
        open.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(open);
        return AsmPatchSupport.write(node);
    }
}
