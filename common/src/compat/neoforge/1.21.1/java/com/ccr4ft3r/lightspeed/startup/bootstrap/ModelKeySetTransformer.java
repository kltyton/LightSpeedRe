package com.ccr4ft3r.lightspeed.startup.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/** Replaces ObjectLinkedOpenHashSet allocations in GAME-layer model builders with ModelKeySet via ModLauncher SPI. */
public final class ModelKeySetTransformer implements ITransformer<ClassNode> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelKeySetTransformer.class);
    private static final String ORIGINAL = "it/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet";
    private static final String REPLACEMENT = "com/ccr4ft3r/lightspeed/client/model/ModelKeySet";
    private static final Set<Target<ClassNode>> TARGETS = Set.of(
            Target.targetClass("org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper"),
            Target.targetClass("net.minecraft.client.resources.model.ModelBakery"),
            Target.targetClass("net.minecraft.client.resources.model.ModelManager"),
            Target.targetClass("net.minecraft.client.resources.model.BlockStateModelLoader")
    );

    @Override
    public ClassNode transform(ClassNode node, ITransformerVotingContext context) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!supportsReplacement(node, method)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && ORIGINAL.equals(type.desc)) {
                    type.desc = REPLACEMENT;
                    changed = true;
                } else if (instruction instanceof MethodInsnNode call && ORIGINAL.equals(call.owner)
                        && "<init>".equals(call.name)) {
                    call.owner = REPLACEMENT;
                }
            }
        }
        if (changed) {
            LOGGER.info("Lightspeed transformed {} with ModelKeySet", node.name);
        }
        return node;
    }

    private static boolean supportsReplacement(ClassNode node, MethodNode method) {
        if (ORIGINAL.equals(node.superName) && "<init>".equals(method.name)) {
            return false;
        }
        boolean allocated = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && ORIGINAL.equals(type.desc)) {
                allocated = true;
            } else if (instruction instanceof MethodInsnNode call && ORIGINAL.equals(call.owner)
                    && "<init>".equals(call.name)
                    && !"()V".equals(call.desc) && !"(I)V".equals(call.desc) && !"(IF)V".equals(call.desc)) {
                return false;
            }
        }
        return allocated;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return TARGETS;
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }
}
