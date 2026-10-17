package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;

public final class ModConstructionPatch {
    private static final String INFO = "Lnet/minecraftforge/forgespi/language/IModInfo;";
    private static final String SCAN = "Lnet/minecraftforge/forgespi/language/ModFileScanData;";
    private static final String ARGUMENTS = "(" + INFO + "Ljava/lang/String;" + SCAN + "Ljava/lang/ModuleLayer;)V";

    private ModConstructionPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode constructor = AsmPatchSupport.method(node, "<init>", ARGUMENTS);
        MethodNode construct = AsmPatchSupport.method(node, "constructMod", "()V");
        if (constructor.tryCatchBlocks.size() != 1) throw new IllegalStateException("unexpected class-load boundary");
        TryCatchBlockNode loadBoundary = constructor.tryCatchBlocks.get(0);
        AsmPatchSupport.uniqueInvocation(constructor, Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;");

        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "lightspeed$className", "Ljava/lang/String;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "lightspeed$gameLayer", "Ljava/lang/ModuleLayer;", null, null));
        node.fields.stream().filter(field -> field.name.equals("modClass"))
                .forEach(field -> field.access &= ~Opcodes.ACC_FINAL);

        // Keep the original module lookup, logging and error conversion together.
        // CONSTRUCT already runs on Forge workers; container creation stays ordered.
        MethodNode load = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                "lightspeed$loadModClass", ARGUMENTS, null, null);
        AbstractInsnNode instruction = loadBoundary.start;
        while (instruction != null) {
            AbstractInsnNode next = instruction.getNext();
            constructor.instructions.remove(instruction);
            load.instructions.add(instruction);
            instruction = next;
        }
        load.tryCatchBlocks = new ArrayList<>(constructor.tryCatchBlocks);
        load.maxLocals = constructor.maxLocals;
        load.maxStack = constructor.maxStack;
        constructor.tryCatchBlocks.clear();
        constructor.localVariables = null;
        node.methods.add(load);

        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name,
                "lightspeed$className", "Ljava/lang/String;"));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name,
                "lightspeed$gameLayer", "Ljava/lang/ModuleLayer;"));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));

        InsnList prepare = new InsnList();
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getModInfo", "()" + INFO, false));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "lightspeed$className", "Ljava/lang/String;"));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "scanResults", SCAN));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "lightspeed$gameLayer", "Ljava/lang/ModuleLayer;"));
        prepare.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, load.name, ARGUMENTS, false));
        construct.instructions.insert(prepare);
        return AsmPatchSupport.write(node);
    }
}
