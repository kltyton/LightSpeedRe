package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class ModuleLayerPatch {
    private static final String RESOLVE_DESCRIPTOR =
            "(Ljava/lang/module/ModuleFinder;Ljava/util/List;Ljava/lang/module/ModuleFinder;Ljava/util/Collection;)Ljava/lang/module/Configuration;";

    private ModuleLayerPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("buildLayer"))
                .filter(candidate -> candidate.desc.contains("Ljava/util/function/BiFunction;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing ModuleLayerHandler.buildLayer"));
        MethodInsnNode invocation = AsmPatchSupport.uniqueInvocation(method, Opcodes.INVOKESTATIC,
                "java/lang/module/Configuration", "resolveAndBind", RESOLVE_DESCRIPTOR);
        invocation.owner = AsmPatchSupport.HOOK_OWNER;
        invocation.name = "resolveAndBind";
        invocation.itf = false;
        return AsmPatchSupport.write(node);
    }
}
