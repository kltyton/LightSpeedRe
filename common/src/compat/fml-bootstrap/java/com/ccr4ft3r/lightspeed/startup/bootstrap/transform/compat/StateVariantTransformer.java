package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.CompatClassTransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Replaces ResourcefulLib's {@code StateVariant.getStates(StateDefinition, String)} with
 * {@link com.ccr4ft3r.lightspeed.startup.compat.StateVariantSupport#getStates} to avoid
 * Guava {@code Splitter}, {@code HashMap}, and {@code Stream} overhead during initial reload.
 */
public final class StateVariantTransformer extends CompatClassTransformer {
    private static final String TARGET = "com.teamresourceful.resourcefullib.client.highlights.state.StateVariant";
    private static final String SUPPORT = "com/ccr4ft3r/lightspeed/startup/compat/StateVariantSupport";
    private static final String DESC =
            "(Lnet/minecraft/world/level/block/state/StateDefinition;Ljava/lang/String;)Lcom/mojang/serialization/DataResult;";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        for (MethodNode method : input.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0
                    && "getStates".equals(method.name)
                    && DESC.equals(method.desc)) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) {
                    method.localVariables.clear();
                }
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                method.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        SUPPORT,
                        "getStates",
                        DESC,
                        false
                ));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
                method.maxStack = 2;
                method.maxLocals = 2;
                break;
            }
        }
        return input;
    }

    @Override
    protected Set<String> targetClassNames() {
        return Set.of(TARGET);
    }
}
