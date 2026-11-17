package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.JumpInsnNode;

import java.util.List;

public final class MixinTargetIndexPatch {
    private MixinTargetIndexPatch() {
    }

    public static byte[] processor(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode apply = uniqueMethod(node, "applyMixins");
        if ((apply.access & Opcodes.ACC_SYNCHRONIZED) == 0) {
            throw new IllegalStateException("Mixin processor no longer holds its monitor");
        }
        int reads = 0;
        for (AbstractInsnNode instruction : apply.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(node.name) && field.name.equals("configs")) {
                if (++reads == 2) {
                    InsnList select = new InsnList();
                    select.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    select.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    select.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                            "mixinConfigsFor", "(Ljava/util/List;Ljava/lang/Object;Ljava/lang/String;)Ljava/util/List;", false));
                    apply.instructions.insert(instruction, select);
                }
            }
        }
        if (reads != 2) {
            throw new IllegalStateException("unexpected Mixin configuration traversals: " + reads);
        }
        for (AbstractInsnNode instruction : apply.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(node.name) && field.name.equals("transformedCount")) {
                apply.instructions.insert(instruction, publishCall(node.name));
            }
        }
        MethodNode select = uniqueMethod(node, "select");
        for (AbstractInsnNode instruction : select.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                select.instructions.insertBefore(instruction, publishCall(node.name));
            }
        }
        MethodNode prepare = uniqueMethod(node, "prepareConfigs");
        MethodInsnNode sort = AsmPatchSupport.uniqueInvocation(prepare, Opcodes.INVOKESTATIC,
                "java/util/Collections", "sort", "(Ljava/util/List;)V");
        prepare.instructions.insert(sort, invalidation());
        return AsmPatchSupport.write(node);
    }

    public static byte[] configuration(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode mapping = AsmPatchSupport.method(node, "mixinsFor", "(Ljava/lang/String;)Ljava/util/List;");
        MethodInsnNode put = AsmPatchSupport.uniqueInvocation(mapping, Opcodes.INVOKEINTERFACE,
                "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        mapping.instructions.insert(put, invalidation());
        return AsmPatchSupport.write(node);
    }

    public static byte[] generators(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode add = AsmPatchSupport.method(node, "add",
                "(Lorg/spongepowered/asm/mixin/transformer/ext/IClassGenerator;)V");
        for (AbstractInsnNode instruction : add.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) add.instructions.insertBefore(instruction, invalidation());
        }
        return AsmPatchSupport.write(node);
    }

    public static byte[] launchPlugin(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode handles = node.methods.stream().filter(method -> method.name.equals("handlesClass")
                && Type.getArgumentTypes(method.desc).length == 3).findFirst().orElseThrow();
        LabelNode original = new LabelNode();
        InsnList fast = new InsnList();
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new VarInsnNode(Opcodes.ILOAD, 2));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "mixinPhases",
                "(Ljava/lang/Object;Ljava/lang/Object;ZLjava/lang/String;)Ljava/util/EnumSet;", false));
        fast.add(new InsnNode(Opcodes.DUP));
        fast.add(new JumpInsnNode(Opcodes.IFNULL, original));
        fast.add(new InsnNode(Opcodes.ARETURN));
        fast.add(original);
        fast.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/util/EnumSet"}));
        fast.add(new InsnNode(Opcodes.POP));
        handles.instructions.insert(fast);

        MethodNode process = node.methods.stream().filter(method -> method.name.equals("processClass")
                && Type.getArgumentTypes(method.desc).length == 4).findFirst().orElseThrow();
        String typeOwner = Type.getArgumentTypes(process.desc)[2].getInternalName();
        LabelNode originalProcess = new LabelNode();
        InsnList skip = new InsnList();
        skip.add(new VarInsnNode(Opcodes.ALOAD, 2));
        skip.add(new JumpInsnNode(Opcodes.IFNULL, originalProcess));
        skip.add(new VarInsnNode(Opcodes.ALOAD, 0));
        skip.add(new VarInsnNode(Opcodes.ALOAD, 1));
        skip.add(new VarInsnNode(Opcodes.ALOAD, 3));
        skip.add(new VarInsnNode(Opcodes.ALOAD, 3));
        skip.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, typeOwner,
                "getClassName", "()Ljava/lang/String;", false));
        skip.add(new VarInsnNode(Opcodes.ALOAD, 4));
        skip.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "skipMixinPlugin", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Z", false));
        skip.add(new JumpInsnNode(Opcodes.IFEQ, originalProcess));
        skip.add(new InsnNode(Opcodes.ICONST_0));
        skip.add(new InsnNode(Opcodes.IRETURN));
        skip.add(originalProcess);
        skip.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        process.instructions.insert(skip);
        return AsmPatchSupport.write(node);
    }

    private static InsnList publishCall(String owner) {
        InsnList publish = new InsnList();
        publish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        publish.add(new VarInsnNode(Opcodes.ALOAD, 1));
        publish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        publish.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "configs", "Ljava/util/List;"));
        publish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        publish.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "coprocessors",
                "Lorg/spongepowered/asm/mixin/transformer/MixinCoprocessors;"));
        publish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        publish.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "hotSwapper",
                "Lorg/spongepowered/asm/mixin/transformer/ext/IHotSwap;"));
        publish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        publish.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "extensions",
                "Lorg/spongepowered/asm/mixin/transformer/ext/Extensions;"));
        publish.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "org/spongepowered/asm/mixin/transformer/ext/Extensions", "getGenerators", "()Ljava/util/List;", false));
        publish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "publishMixinClassIndex", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;)V", false));
        return publish;
    }

    private static MethodInsnNode invalidation() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "invalidateMixinTargetIndex", "()V", false);
    }

    private static MethodNode uniqueMethod(ClassNode node, String name) {
        List<MethodNode> methods = node.methods.stream().filter(method -> method.name.equals(name)).toList();
        if (methods.size() != 1) {
            throw new IllegalStateException("expected one Mixin method " + name);
        }
        return methods.get(0);
    }
}
