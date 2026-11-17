package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;

/** Limits ZIP write-access suppression to the backing files of a read-only Union root. */
public final class ReadOnlyArchiveOpenPatch {
    private static final String WRITABLE = "(Ljava/nio/file/Path;)Z";
    private static final Handle INVOKE = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/ConstantBootstraps", "invoke",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                    + "Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;", false);

    private ReadOnlyArchiveOpenPatch() { }

    public static byte[] writableMethodBytes(byte[] bytes) {
        var node = AsmPatchSupport.read(bytes);
        var method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("lambda$new$1")
                        && candidate.desc.equals("(Ljava/nio/file/Path;)Ljava/lang/Boolean;"))
                .findFirst().orElse(null);
        if (method == null) return bytes;
        StringBuilder signature = new StringBuilder(node.name).append('.').append(method.name)
                .append(method.desc).append(':').append(method.access);
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            signature.append('\n').append(opcode);
            if (instruction instanceof VarInsnNode variable) {
                signature.append(':').append(variable.var);
            } else if (instruction instanceof MethodInsnNode call) {
                signature.append(':').append(call.owner).append('.').append(call.name)
                        .append(call.desc).append(':').append(call.itf);
            } else if (!(instruction instanceof InsnNode)) {
                return bytes;
            }
        }
        return signature.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] union(byte[] bytes) {
        var node = AsmPatchSupport.read(bytes);
        var open = AsmPatchSupport.method(node, "openFileSystem", "(Ljava/nio/file/Path;)Ljava/util/Optional;");
        MethodInsnNode invocation = AsmPatchSupport.uniqueInvocation(open, Opcodes.INVOKESTATIC,
                "java/nio/file/FileSystems", "newFileSystem", "(Ljava/nio/file/Path;)Ljava/nio/file/FileSystem;");
        invocation.owner = AsmPatchSupport.HOOK_OWNER;
        invocation.name = "openReadOnlyArchive";
        return AsmPatchSupport.write(node);
    }

    public static byte[] zipFileSystem(byte[] bytes) {
        var node = AsmPatchSupport.read(bytes);
        var writable = AsmPatchSupport.method(node, "lambda$new$1", "(Ljava/nio/file/Path;)Ljava/lang/Boolean;");
        MethodInsnNode invocation = AsmPatchSupport.uniqueInvocation(writable, Opcodes.INVOKESTATIC,
                "java/nio/file/Files", "isWritable", WRITABLE);
        InsnList scoped = new InsnList();
        scoped.add(new LdcInsnNode(writableHandle()));
        scoped.add(new InsnNode(Opcodes.SWAP));
        scoped.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
                "invokeExact", WRITABLE, false));
        writable.instructions.insertBefore(invocation, scoped);
        writable.instructions.remove(invocation);
        return AsmPatchSupport.write(node);
    }

    private static ConstantDynamic writableHandle() {
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
        return new ConstantDynamic("lightspeedArchiveWritable", "Ljava/lang/invoke/MethodHandle;", INVOKE,
                new Handle(Opcodes.H_INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStatic",
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false),
                lookup, hooks, "archiveWritable", Type.getMethodType(WRITABLE));
    }
}
