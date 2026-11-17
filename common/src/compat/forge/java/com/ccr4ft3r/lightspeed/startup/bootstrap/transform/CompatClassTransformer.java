package com.ccr4ft3r.lightspeed.startup.bootstrap.transform;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashSet;
import java.util.Set;

/** Forge 1.20.1 adapter for loader-neutral {@link ClassNode} transformers. */
public abstract class CompatClassTransformer implements ITransformer<ClassNode> {
    protected abstract Set<String> targetClassNames();

    /**
     * ModLauncher 10 checks {@code getClass().getGenericInterfaces()} without walking superclasses,
     * so subclassed {@link CompatClassTransformer} instances must be wrapped in a class that
     * directly implements {@code ITransformer<ClassNode>}.
     */
    @SuppressWarnings("rawtypes")
    public static ITransformer ensureDirect(ITransformer transformer) {
        if (!(transformer instanceof CompatClassTransformer compat)) {
            return transformer;
        }
        return new ITransformer<ClassNode>() {
            @Override
            public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
                return compat.transform(input, context);
            }

            @Override
            public TransformerVoteResult castVote(ITransformerVotingContext context) {
                return compat.castVote(context);
            }

            @Override
            public Set<Target> targets() {
                return compat.targets();
            }

            @Override
            public String[] labels() {
                return compat.labels();
            }
        };
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public final Set<Target> targets() {
        Set<String> names = targetClassNames();
        Set<Target> result = new HashSet<>(names.size());
        for (String name : names) {
            result.add(Target.targetClass(name));
        }
        return result;
    }
}
