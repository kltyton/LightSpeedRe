package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.CompatClassTransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Replaces {@code Map.computeIfAbsent(variant, Function.identity())} in FerriteCore
 * {@code Deduplicator.deduplicateVariant} with lock-free {@code Map.get} fast path and
 * {@code Map.putIfAbsent(variant, variant)} on misses.
 */
public final class DeduplicatorTransformer extends CompatClassTransformer {
    private static final String TARGET = "malte0811.ferritecore.impl.Deduplicator";
    private static final String TARGET_INTERNAL = "malte0811/ferritecore/impl/Deduplicator";
    private static final String METHOD_NAME = "deduplicateVariant";
    private static final String METHOD_DESC = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String VARIANTS_FIELD = "VARIANT_IDENTITIES";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        MethodNode method = findTargetMethod(input);
        if (method == null || !hasVariantMapField(method)) {
            return input;
        }
        LabelNode missLabel = new LabelNode();
        LabelNode returnArgLabel = new LabelNode();

        InsnList rewritten = new InsnList();
        rewritten.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET_INTERNAL, VARIANTS_FIELD, "Ljava/util/Map;"));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rewritten.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true
        ));
        rewritten.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        rewritten.add(new VarInsnNode(Opcodes.ASTORE, 1));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rewritten.add(new JumpInsnNode(Opcodes.IFNULL, missLabel));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rewritten.add(new InsnNode(Opcodes.ARETURN));

        rewritten.add(missLabel);
        rewritten.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null));
        rewritten.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET_INTERNAL, VARIANTS_FIELD, "Ljava/util/Map;"));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rewritten.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true
        ));
        rewritten.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        rewritten.add(new VarInsnNode(Opcodes.ASTORE, 2));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 2));
        rewritten.add(new JumpInsnNode(Opcodes.IFNULL, returnArgLabel));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 2));
        rewritten.add(new InsnNode(Opcodes.ARETURN));

        rewritten.add(returnArgLabel);
        rewritten.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null));
        rewritten.add(new VarInsnNode(Opcodes.ALOAD, 0));
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
        method.maxLocals = Math.max(method.maxLocals, 3);
        return input;
    }

    @Override
    protected Set<String> targetClassNames() {
        return Set.of(TARGET);
    }

    private static MethodNode findTargetMethod(ClassNode input) {
        for (MethodNode method : input.methods) {
            if (METHOD_NAME.equals(method.name) && METHOD_DESC.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static boolean hasVariantMapField(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && TARGET_INTERNAL.equals(field.owner)
                    && VARIANTS_FIELD.equals(field.name)
                    && "Ljava/util/Map;".equals(field.desc)) {
                return true;
            }
        }
        return false;
    }
}
