package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public final class AccessTransformerIndexPatch {
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    private AccessTransformerIndexPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode contains = node.methods.stream().filter(m -> m.name.equals("containsClassTarget")).findFirst().orElseThrow();
        MethodNode select = node.methods.stream().filter(m -> m.name.equals("getTransformersForTarget")).findFirst().orElseThrow();
        String asmType = Type.getArgumentTypes(contains.desc)[0].getDescriptor();

        contains.instructions.clear(); contains.tryCatchBlocks.clear();
        if (contains.localVariables != null) contains.localVariables.clear();
        contains.instructions.add(index(node.name, asmType));
        contains.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        contains.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z", true));
        contains.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodInsnNode map = AsmPatchSupport.uniqueInvocation(select, Opcodes.INVOKEINTERFACE,
                "java/util/stream/Stream", "map", "(Ljava/util/function/Function;)Ljava/util/stream/Stream;");
        AbstractInsnNode collector = map.getNext();
        while (select.instructions.getFirst() != collector) select.instructions.remove(select.instructions.getFirst());
        if (select.localVariables != null) select.localVariables.clear();
        InsnList selected = index(node.name, asmType);
        selected.add(new VarInsnNode(Opcodes.ALOAD, 1));
        selected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false));
        selected.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        selected.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
        selected.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "stream", "()Ljava/util/stream/Stream;", true));
        select.instructions.insert(selected);

        MethodNode load = node.methods.stream().filter(m -> m.name.equals("loadFromPath")).findFirst().orElseThrow();
        MethodInsnNode commit = AsmPatchSupport.uniqueInvocation(load, Opcodes.INVOKEINTERFACE,
                "java/util/Map", "putAll", "(Ljava/util/Map;)V");
        InsnList invalidate = new InsnList();
        invalidate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        invalidate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "invalidateAccessTransformers", "(Ljava/lang/Object;)V", false));
        load.instructions.insert(commit, invalidate);
        return AsmPatchSupport.write(node);
    }

    /** Patches NeoForge 10.x AccessTransformerList to index rules by target Type/TargetType/name. */
    public static byte[] applyNamed(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode contains = AsmPatchSupport.method(node, "containsClassTarget", "(Lorg/objectweb/asm/Type;)Z");
        String asmType = Type.getArgumentTypes(contains.desc)[0].getDescriptor();
        MethodNode select = AsmPatchSupport.method(node, "getTransformersForTarget",
                "(" + asmType + ")Ljava/util/Map;");

        select.instructions.clear();
        select.tryCatchBlocks.clear();
        if (select.localVariables != null) {
            select.localVariables.clear();
        }
        InsnList selected = new InsnList();
        selected.add(new VarInsnNode(Opcodes.ALOAD, 0));
        selected.add(new VarInsnNode(Opcodes.ALOAD, 0));
        selected.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "accessTransformers", "Ljava/util/Map;"));
        String targetClass = "net/neoforged/accesstransformer/Target";
        String targetTypeDesc = "Lnet/neoforged/accesstransformer/TargetType;";
        selected.add(targetLambda(targetClass, "getASMType", "()" + asmType, "(L" + targetClass + ";)" + asmType));
        selected.add(targetLambda(targetClass, "getType", "()" + targetTypeDesc,
                "(L" + targetClass + ";)" + targetTypeDesc));
        selected.add(targetLambda(targetClass, "targetName", "()Ljava/lang/String;",
                "(L" + targetClass + ";)Ljava/lang/String;"));
        selected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "accessTransformersNamedFor",
                "(Ljava/lang/Object;Ljava/util/Map;Ljava/util/function/Function;"
                        + "Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/util/Map;",
                false));
        selected.add(new VarInsnNode(Opcodes.ALOAD, 1));
        selected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections",
                "emptyMap", "()Ljava/util/Map;", false));
        selected.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map",
                "getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        selected.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
        selected.add(new InsnNode(Opcodes.ARETURN));
        select.instructions.add(selected);

        MethodNode load = AsmPatchSupport.method(node, "loadAT", "(Lorg/antlr/v4/runtime/CharStream;)V");
        MethodInsnNode commit = AsmPatchSupport.uniqueInvocation(load, Opcodes.INVOKEINTERFACE,
                "java/util/Map", "putAll", "(Ljava/util/Map;)V");
        InsnList invalidate = new InsnList();
        invalidate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        invalidate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "invalidateAccessTransformers", "(Ljava/lang/Object;)V", false));
        load.instructions.insert(commit, invalidate);
        return AsmPatchSupport.write(node);
    }

    private static InvokeDynamicInsnNode targetLambda(String targetClass, String methodName,
                                                      String virtualDesc, String samSpecializedDesc) {
        return new InvokeDynamicInsnNode("apply", "()Ljava/util/function/Function;", METAFACTORY,
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKEVIRTUAL, targetClass, methodName, virtualDesc, false),
                Type.getMethodType(samSpecializedDesc));
    }

    private static InsnList index(String owner, String asmType) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "accessTransformers", "Ljava/util/Map;"));
        code.add(new InvokeDynamicInsnNode("apply", "()Ljava/util/function/Function;", METAFACTORY,
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKEVIRTUAL, "net/minecraftforge/accesstransformer/Target", "getASMType", "()" + asmType, false),
                Type.getMethodType("(Lnet/minecraftforge/accesstransformer/Target;)" + asmType)));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "accessTransformersFor", "(Ljava/lang/Object;Ljava/util/Map;Ljava/util/function/Function;)Ljava/util/Map;", false));
        return code;
    }
}
