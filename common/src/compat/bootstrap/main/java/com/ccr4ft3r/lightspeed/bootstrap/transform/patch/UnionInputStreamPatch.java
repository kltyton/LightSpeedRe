package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public final class UnionInputStreamPatch {
    private static final String INPUT = "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;";
    private static final Handle INVOKE = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/ConstantBootstraps", "invoke",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                    + "Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;", false);

    private UnionInputStreamPatch() { }

    public static byte[] jdkProvider(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode open = AsmPatchSupport.method(node, "newInputStream", INPUT);
        if ((open.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("Unexpected FileSystemProvider input method");
        }
        LabelNode original = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false));
        code.add(new LdcInsnNode("cpw.mods.niofs.union.UnionFileSystemProvider"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, original));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, original));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false));
        code.add(new LdcInsnNode("cpw.mods.niofs.union.UnionPath"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, original));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/nio/file/Path", "getFileSystem", "()Ljava/nio/file/FileSystem;", true));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false));
        code.add(new LdcInsnNode("cpw.mods.niofs.union.UnionFileSystem"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, original));
        code.add(new LdcInsnNode(inputHandle()));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", INPUT, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(original);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        open.instructions.insert(code);
        return AsmPatchSupport.write(node);
    }

    private static ConstantDynamic inputHandle() {
        ConstantDynamic systemLoader = new ConstantDynamic("lightspeedLoader", "Ljava/lang/ClassLoader;", INVOKE,
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader",
                        "()Ljava/lang/ClassLoader;", false));
        ConstantDynamic hooks = new ConstantDynamic("lightspeedHooks", "Ljava/lang/Class;", INVOKE,
                new Handle(Opcodes.H_INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass",
                        "(Ljava/lang/String;)Ljava/lang/Class;", false),
                systemLoader, AsmPatchSupport.HOOK_OWNER.replace('/', '.'));
        ConstantDynamic lookup = new ConstantDynamic("lightspeedLookup", "Ljava/lang/invoke/MethodHandles$Lookup;",
                INVOKE, new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/MethodHandles", "publicLookup",
                        "()Ljava/lang/invoke/MethodHandles$Lookup;", false));
        return new ConstantDynamic("lightspeedInput", "Ljava/lang/invoke/MethodHandle;", INVOKE,
                new Handle(Opcodes.H_INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStatic",
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false),
                lookup, hooks, "openUnionInput", Type.getMethodType(INPUT));
    }
}
