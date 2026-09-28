package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

public final class KeyMappingLookupPatch {
    private KeyMappingLookupPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<MethodNode> wrappers = new ArrayList<>();
        for (MethodNode body : node.methods) {
            if ((body.access & Opcodes.ACC_PUBLIC) == 0 || body.name.startsWith("<")) continue;
            MethodNode wrapper = new MethodNode(body.access, body.name, body.desc, body.signature,
                    body.exceptions.toArray(String[]::new));
            wrapper.visibleAnnotations = body.visibleAnnotations;
            wrapper.invisibleAnnotations = body.invisibleAnnotations;
            body.name = "lightspeed$locked$" + body.name;
            body.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
            Type[] arguments = Type.getArgumentTypes(body.desc);
            int monitor = 1;
            List<Object> locals = new ArrayList<>();
            locals.add(node.name);
            for (Type argument : arguments) {
                if (argument.getSort() != Type.OBJECT) throw new IllegalStateException("unexpected lookup argument");
                locals.add(argument.getInternalName());
                monitor += argument.getSize();
            }
            locals.add("java/lang/Class");
            LabelNode start = new LabelNode();
            LabelNode end = new LabelNode();
            LabelNode failure = new LabelNode();
            InsnList code = wrapper.instructions;
            code.add(new LdcInsnNode(Type.getObjectType(node.name)));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new VarInsnNode(Opcodes.ASTORE, monitor));
            code.add(new InsnNode(Opcodes.MONITORENTER));
            code.add(start);
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            int local = 1;
            for (Type argument : arguments) {
                code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
                local += argument.getSize();
            }
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, body.name, body.desc, false));
            code.add(end);
            code.add(new VarInsnNode(Opcodes.ALOAD, monitor));
            code.add(new InsnNode(Opcodes.MONITOREXIT));
            code.add(new InsnNode(Type.getReturnType(body.desc).getOpcode(Opcodes.IRETURN)));
            code.add(failure);
            code.add(new FrameNode(Opcodes.F_FULL, locals.size(), locals.toArray(), 1,
                    new Object[]{"java/lang/Throwable"}));
            code.add(new VarInsnNode(Opcodes.ASTORE, monitor + 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, monitor));
            code.add(new InsnNode(Opcodes.MONITOREXIT));
            code.add(new VarInsnNode(Opcodes.ALOAD, monitor + 1));
            code.add(new InsnNode(Opcodes.ATHROW));
            wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failure, null));
            wrapper.maxLocals = monitor + 2;
            wrappers.add(wrapper);
        }
        if (wrappers.size() != 5) throw new IllegalStateException("unexpected key lookup API");
        node.methods.addAll(wrappers);
        return AsmPatchSupport.write(node);
    }
}
