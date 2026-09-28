package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ResourceLookupPatch {
    private ResourceLookupPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        return apply(bytes, false);
    }

    public static byte[] applyWithRegistration(byte[] bytes) {
        return apply(bytes, true);
    }

    private static byte[] apply(byte[] bytes, boolean registerRoot) {
        ClassNode node = AsmPatchSupport.read(bytes);
        if (registerRoot) {
            registerResourceRoot(node);
            cachePackages(node);
            skipUnsignedVerification(node);
        }
        MethodNode method = AsmPatchSupport.method(node, "findFile", "(Ljava/lang/String;)Ljava/util/Optional;");
        LabelNode proceed = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getRootPath", "()Ljava/nio/file/Path;", false));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getPrimaryPath", "()Ljava/nio/file/Path;", false));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "mightContain",
                "(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/lang/String;)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, proceed));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
        guard.add(new InsnNode(Opcodes.ARETURN));
        guard.add(proceed);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insertBefore(method.instructions.getFirst(), guard);
        if (registerRoot) directModulePath(node, method);
        return AsmPatchSupport.write(node);
    }

    private static void directModulePath(ClassNode node, MethodNode findFile) {
        MethodNode direct = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "lightspeed$findPath", findFile.desc, null, findFile.exceptions.toArray(String[]::new));
        findFile.accept(direct);
        MethodInsnNode uriMap = AsmPatchSupport.uniqueInvocation(direct, Opcodes.INVOKEVIRTUAL,
                "java/util/Optional", "map", "(Ljava/util/function/Function;)Ljava/util/Optional;");
        AbstractInsnNode mapper = uriMap.getPrevious();
        while (mapper.getOpcode() < 0) mapper = mapper.getPrevious();
        if (!(mapper instanceof InvokeDynamicInsnNode)) {
            throw new IllegalStateException("Unexpected SecureJar URI conversion");
        }
        direct.instructions.remove(mapper);
        direct.instructions.remove(uriMap);
        node.methods.add(direct);
    }

    private static void registerResourceRoot(ClassNode node) {
        MethodNode constructor = AsmPatchSupport.method(node, "<init>",
                "(Ljava/util/function/Supplier;Ljava/util/function/Function;Ljava/util/function/BiPredicate;[Ljava/nio/file/Path;)V");
        FieldInsnNode filesystemWrite = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(node.name)
                    && field.name.equals("filesystem")) {
                if (filesystemWrite != null) {
                    throw new IllegalStateException("duplicate filesystem assignment");
                }
                filesystemWrite = field;
            }
        }
        if (filesystemWrite == null) {
            throw new IllegalStateException("missing filesystem assignment");
        }

        InsnList registration = new InsnList();
        registration.add(new VarInsnNode(Opcodes.ALOAD, 0));
        registration.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "filesystem",
                "Lcpw/mods/niofs/union/UnionFileSystem;"));
        registration.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionFileSystem",
                "getRoot", "()Ljava/nio/file/Path;", false));
        registration.add(new VarInsnNode(Opcodes.ALOAD, 0));
        registration.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "filesystem",
                "Lcpw/mods/niofs/union/UnionFileSystem;"));
        registration.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionFileSystem",
                "getPrimaryPath", "()Ljava/nio/file/Path;", false));
        registration.add(new VarInsnNode(Opcodes.ALOAD, 3));
        registration.add(new VarInsnNode(Opcodes.ALOAD, 4));
        registration.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "registerResourceRoot",
                "(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/util/function/BiPredicate;[Ljava/nio/file/Path;)V",
                false));
        constructor.instructions.insert(filesystemWrite, registration);
    }

    private static void cachePackages(ClassNode node) {
        MethodNode method = AsmPatchSupport.method(node, "getPackages", "()Ljava/util/Set;");
        LabelNode unavailable = new LabelNode();
        LabelNode proceed = new LabelNode();
        InsnList lookup = new InsnList();
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        lookup.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "packages", "Ljava/util/Set;"));
        lookup.add(new JumpInsnNode(Opcodes.IFNONNULL, proceed));
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        lookup.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getRootPath",
                "()Ljava/nio/file/Path;", false));
        lookup.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "resourcePackages",
                "(Ljava/nio/file/Path;)Ljava/util/Set;", false));
        lookup.add(new InsnNode(Opcodes.DUP));
        lookup.add(new JumpInsnNode(Opcodes.IFNULL, unavailable));
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        lookup.add(new InsnNode(Opcodes.SWAP));
        lookup.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "packages", "Ljava/util/Set;"));
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        lookup.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "packages", "Ljava/util/Set;"));
        lookup.add(new InsnNode(Opcodes.ARETURN));
        lookup.add(unavailable);
        lookup.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/util/Set"}));
        lookup.add(new InsnNode(Opcodes.POP));
        lookup.add(proceed);
        lookup.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insertBefore(method.instructions.getFirst(), lookup);
    }

    private static void skipUnsignedVerification(ClassNode node) {
        MethodNode verify = AsmPatchSupport.method(node, "verifyPath",
                "(Ljava/nio/file/Path;)Lcpw/mods/jarhandling/SecureJar$Status;");
        MethodInsnNode pathString = AsmPatchSupport.uniqueInvocation(verify, Opcodes.INVOKEINTERFACE,
                "java/nio/file/Path", "toString", "()Ljava/lang/String;");
        AbstractInsnNode pathLoad = pathString.getPrevious();
        while (pathLoad.getOpcode() < 0) pathLoad = pathLoad.getPrevious();
        if (!(pathLoad instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 1) {
            throw new IllegalStateException("Unexpected SecureJar path validation");
        }
        LabelNode original = new LabelNode();
        InsnList fast = new InsnList();
        // Unsigned regular entries have no signer work; signed entries keep the original byte verification.
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name,
                "hasSecurityData", "()Z", false));
        fast.add(new JumpInsnNode(Opcodes.IFNE, original));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new InsnNode(Opcodes.ICONST_0));
        fast.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/nio/file/LinkOption"));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/nio/file/Files",
                "isRegularFile", "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z", false));
        fast.add(new JumpInsnNode(Opcodes.IFEQ, original));
        fast.add(new FieldInsnNode(Opcodes.GETSTATIC, "cpw/mods/jarhandling/SecureJar$Status",
                "UNVERIFIED", "Lcpw/mods/jarhandling/SecureJar$Status;"));
        fast.add(new InsnNode(Opcodes.ARETURN));
        fast.add(original);
        fast.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        verify.instructions.insertBefore(pathLoad, fast);
    }
}
