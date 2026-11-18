package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerActivity;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Preserves the two Forge biome redirects when a launch access transformer widens their fields. */
public final class BiomeFieldRedirects {
    private static final String BIOME = "net.minecraft.world.level.biome.Biome";
    private static final int VISIBILITY = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
    private static final String CLIMATE_GETTER = "getModifiedClimateSettings";
    private static final String EFFECTS_GETTER = "getModifiedSpecialEffects";

    private final Map<ITransformerVotingContext, Access> pending = Collections.synchronizedMap(new WeakHashMap<>());
    private record Access(FieldNode climate, FieldNode effects, int climateVisibility, int effectsVisibility,
                          FieldInsnNode climateRead, FieldInsnNode effectsRead, int firstActivity) { }

    public ITransformer<ClassNode> prepare() {
        return new ITransformer<ClassNode>() {
            @Override
            public Set<Target> targets() {
                return Set.of(Target.targetPreClass(BIOME));
            }

            @Override
            public TransformerVoteResult castVote(ITransformerVotingContext context) {
                return TransformerVoteResult.YES;
            }

            @Override
            public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
                FieldNode climate = field(input, "f_47437_", "climateSettings", "Biome$ClimateSettings");
                FieldNode effects = field(input, "f_47443_", "specialEffects", "BiomeSpecialEffects");
                if (climate == null || effects == null || hasPrivateAccess(climate) && hasPrivateAccess(effects)) {
                    return input;
                }

                FieldInsnNode climateRead = findRawRead(input, climate, CLIMATE_GETTER);
                FieldInsnNode effectsRead = findRawRead(input, effects, EFFECTS_GETTER);
                if (climateRead == null || effectsRead == null) {
                    return input;
                }

                pending.put(context, new Access(climate, effects, climate.access & VISIBILITY,
                        effects.access & VISIBILITY, climateRead, effectsRead, context.getAuditActivities().size()));
                // The BEFORE launch plugin has already run. Narrow visibility only on this transformation's tree.
                climate.access = withVisibility(climate.access, Opcodes.ACC_PRIVATE);
                effects.access = withVisibility(effects.access, Opcodes.ACC_PRIVATE);
                return input;
            }
        };
    }

    public ITransformer<ClassNode> restore() {
        return new ITransformer<ClassNode>() {
            @Override
            public Set<Target> targets() {
                return Set.of(Target.targetClass(BIOME));
            }

            @Override
            public TransformerVoteResult castVote(ITransformerVotingContext context) {
                Access access = pending.get(context);
                if (access == null) {
                    return TransformerVoteResult.NO;
                }
                // Forge replaces the reads in place. Labelling the callback also distinguishes it from other voters.
                boolean redirectsApplied = access.climateRead().getPrevious() == null
                        && access.effectsRead().getPrevious() == null;
                return redirectsApplied && forgeBiomeCompleted(context.getAuditActivities(), access.firstActivity())
                        ? TransformerVoteResult.YES : TransformerVoteResult.DEFER;
            }

            @Override
            public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
                Access access = pending.remove(context);
                for (FieldNode field : input.fields) {
                    if (field.name.equals(access.climate().name) && field.desc.equals(access.climate().desc)) {
                        field.access = withVisibility(field.access, access.climateVisibility());
                    } else if (field.name.equals(access.effects().name) && field.desc.equals(access.effects().desc)) {
                        field.access = withVisibility(field.access, access.effectsVisibility());
                    }
                }
                return input;
            }
        };
    }

    private static FieldNode field(ClassNode input, String srg, String mapped, String type) {
        String desc = "Lnet/minecraft/world/level/biome/" + type + ";";
        return input.fields.stream().filter(field -> (field.name.equals(srg) || field.name.equals(mapped))
                && field.desc.equals(desc) && (field.access & Opcodes.ACC_STATIC) == 0).findFirst().orElse(null);
    }

    private static FieldInsnNode findRawRead(ClassNode input, FieldNode field, String getter) {
        if (input.methods.stream().noneMatch(method -> method.name.equals(getter)
                && method.desc.equals("()" + field.desc) && (method.access & Opcodes.ACC_STATIC) == 0)) {
            return null;
        }
        for (MethodNode method : input.methods) {
            if (method.name.equals(getter)) {
                continue;
            }
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETFIELD
                        && read.owner.equals(input.name) && read.name.equals(field.name) && read.desc.equals(field.desc)) {
                    return read;
                }
            }
        }
        return null;
    }

    private static boolean forgeBiomeCompleted(List<ITransformerActivity> activities, int from) {
        for (int i = from, end = activities.size(); i < end; i++) {
            ITransformerActivity activity = activities.get(i);
            String[] labels = activity.getContext();
            if (activity.getType() == ITransformerActivity.Type.TRANSFORMER && labels.length == 3
                    && labels[0].equals("fml") && labels[1].equals("forge") && labels[2].equals("biome")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPrivateAccess(FieldNode field) {
        return (field.access & VISIBILITY) == Opcodes.ACC_PRIVATE;
    }

    private static int withVisibility(int access, int visibility) {
        return (access & ~VISIBILITY) | visibility;
    }
}
