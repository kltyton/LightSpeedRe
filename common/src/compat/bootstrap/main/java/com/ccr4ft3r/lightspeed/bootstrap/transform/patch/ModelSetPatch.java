package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

public final class ModelSetPatch {
    private static final String ORIGINAL = "it/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet";
    private static final String REPLACEMENT = "com/ccr4ft3r/lightspeed/client/model/ModelKeySet";

    private ModelSetPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        boolean modelCode = false;
        boolean linkedSet = false;
        for (int item = 1; item < reader.getItemCount(); item++) {
            int offset = reader.getItem(item);
            if (offset == 0 || reader.readByte(offset - 1) != 7) continue;
            String name = reader.readUTF8(offset, buffer);
            if (name.startsWith("net/minecraft/client/resources/model/")) modelCode = true;
            if (name.equals(ORIGINAL)) linkedSet = true;
        }
        if (!modelCode || !linkedSet) return null;
        ClassNode node = AsmPatchSupport.read(bytes);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            boolean supported = true;
            boolean allocated = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(ORIGINAL)) {
                    allocated = true;
                }
                if (instruction instanceof MethodInsnNode call && call.owner.equals(ORIGINAL) && call.name.equals("<init>")
                        && !call.desc.equals("()V") && !call.desc.equals("(I)V") && !call.desc.equals("(IF)V")) supported = false;
            }
            if (!allocated || !supported || node.superName.equals(ORIGINAL) && method.name.equals("<init>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(ORIGINAL)) {
                    type.desc = REPLACEMENT;
                    changed = true;
                } else if (instruction instanceof MethodInsnNode call && call.owner.equals(ORIGINAL) && call.name.equals("<init>")) {
                    call.owner = REPLACEMENT;
                }
            }
        }
        return changed ? AsmPatchSupport.write(node) : null;
    }
}
