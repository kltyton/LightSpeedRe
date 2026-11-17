package com.ccr4ft3r.lightspeed.startup.bootstrap.transform;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashSet;
import java.util.Set;

/** NeoForge 1.21.1 adapter for loader-neutral {@link ClassNode} transformers. */
public abstract class CompatClassTransformer implements ITransformer<ClassNode> {
    protected abstract Set<String> targetClassNames();

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public final TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public final Set<Target<ClassNode>> targets() {
        Set<String> names = targetClassNames();
        Set<Target<ClassNode>> result = new HashSet<>(names.size());
        for (String name : names) {
            result.add(Target.targetClass(name));
        }
        return result;
    }
}
