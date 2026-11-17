package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Replaces regex {@code String.split("/")} in Forge {@code PathPackResources.getPathFromLocation}
 * with a non-regex single-pass slash splitter preserving identical path segment semantics.
 */
public final class PathPackResourcesTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net.minecraftforge.resource.PathPackResources";
    private static final String METHOD_NAME = "getPathFromLocation";
    private static final String METHOD_DESC =
            "(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)[Ljava/lang/String;";
    private static final String PACK_TYPE_INTERNAL = "net/minecraft/server/packs/PackType";
    private static final String RESOURCE_LOCATION_INTERNAL = "net/minecraft/resources/ResourceLocation";
    private static final String SUPPORT_INTERNAL = "com/ccr4ft3r/lightspeed/startup/compat/PackResourcesCacheSupport";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        MethodNode method = findTargetMethod(input);
        if (method == null) {
            return input;
        }
        String pathMethod = null;
        String namespaceMethod = null;
        String dirMethod = null;
        boolean hasRegexSplit = false;

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc
                    && "/".equals(ldc.cst)
                    && insn.getNext() instanceof MethodInsnNode splitCall
                    && splitCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "java/lang/String".equals(splitCall.owner)
                    && "split".equals(splitCall.name)
                    && "(Ljava/lang/String;)[Ljava/lang/String;".equals(splitCall.desc)) {
                hasRegexSplit = true;
            } else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                if (RESOURCE_LOCATION_INTERNAL.equals(call.owner) && "()Ljava/lang/String;".equals(call.desc)) {
                    if (pathMethod == null) {
                        pathMethod = call.name;
                    } else if (namespaceMethod == null && !call.name.equals(pathMethod)) {
                        namespaceMethod = call.name;
                    }
                } else if (PACK_TYPE_INTERNAL.equals(call.owner) && "()Ljava/lang/String;".equals(call.desc)) {
                    dirMethod = call.name;
                }
            }
        }

        if (!hasRegexSplit || pathMethod == null || namespaceMethod == null || dirMethod == null) {
            return input;
        }

        InsnList rewritten = new InsnList();
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rewritten.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, PACK_TYPE_INTERNAL, dirMethod, "()Ljava/lang/String;", false));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rewritten.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RESOURCE_LOCATION_INTERNAL, namespaceMethod, "()Ljava/lang/String;", false));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rewritten.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RESOURCE_LOCATION_INTERNAL, pathMethod, "()Ljava/lang/String;", false));
        rewritten.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                SUPPORT_INTERNAL,
                "splitResourcePathWithPrefix",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",
                false
        ));
        rewritten.add(new InsnNode(Opcodes.ARETURN));

        method.instructions.clear();
        method.instructions.add(rewritten);
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks.clear();
        }
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = Math.max(method.maxStack, 3);
        method.maxLocals = Math.max(method.maxLocals, 2);
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return Set.of(Target.targetClass(TARGET));
    }

    private static MethodNode findTargetMethod(ClassNode input) {
        for (MethodNode method : input.methods) {
            if (METHOD_NAME.equals(method.name) && METHOD_DESC.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }
}
