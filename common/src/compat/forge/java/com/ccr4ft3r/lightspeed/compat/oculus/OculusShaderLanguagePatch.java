package com.ccr4ft3r.lightspeed.compat.oculus;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;
import org.spongepowered.asm.util.Annotations;

/** Connects the merged Oculus lookup and legacy writer to the per-dictionary language order. */
public final class OculusShaderLanguagePatch {
    private static final String OCULUS_MIXIN = "net.irisshaders.iris.mixin.MixinClientLanguage";
    private static final String LOAD_CALLBACK = "(Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Ljava/util/List;ZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";

    private OculusShaderLanguagePatch() {
    }

    public static void apply(ClassNode target) {
        MethodNode lookup = null;
        FieldInsnNode lookupOrder = null;
        MethodNode writer = null;
        for (MethodNode method : target.methods) {
            String origin = Annotations.getValue(Annotations.getVisible(method, MixinMerged.class), "mixin");
            if (!OCULUS_MIXIN.equals(origin)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                        || !field.owner.equals(target.name) || !field.name.equals("languageCodes")
                        || !field.desc.equals("Ljava/util/List;")) {
                    continue;
                }
                if (method.name.equals("iris$lookupOverriddenEntry")
                        && method.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                    lookup = method;
                    lookupOrder = field;
                } else if ((method.access & Opcodes.ACC_STATIC) != 0 && method.desc.equals(LOAD_CALLBACK)) {
                    writer = method;
                }
            }
        }
        if (lookup == null || writer == null) {
            return;
        }

        // Only Oculus's short legacy list update needs the monitor. Resource parsing stays concurrent.
        writer.access |= Opcodes.ACC_SYNCHRONIZED;
        lookup.instructions.insertBefore(lookupOrder, new VarInsnNode(Opcodes.ALOAD, 0));
        lookup.instructions.insert(lookupOrder, new MethodInsnNode(Opcodes.INVOKESPECIAL, target.name,
                "lightspeed$shaderLanguageOrder", "(Ljava/util/List;)Ljava/util/List;", false));
        lookup.maxStack += 1;
    }
}
