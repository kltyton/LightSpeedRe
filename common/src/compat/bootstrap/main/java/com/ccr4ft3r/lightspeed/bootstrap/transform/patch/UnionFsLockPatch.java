package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves UnionFileSystem construction outside the provider-global fileSystems monitor so
 * parallel mod-file discovery and resource mounting do not serialize on ZIP archive opens.
 */
public final class UnionFsLockPatch {
    private static final String PROVIDER = "cpw/mods/niofs/union/UnionFileSystemProvider";
    private static final String UNION_FS = "cpw/mods/niofs/union/UnionFileSystem";
    private static final String DESC_SJH2 =
            "(Ljava/lang/String;Ljava/util/function/BiPredicate;[Ljava/nio/file/Path;)Lcpw/mods/niofs/union/UnionFileSystem;";
    private static final String DESC_SJH3 =
            "(Ljava/lang/String;Lcpw/mods/niofs/union/UnionPathFilter;[Ljava/nio/file/Path;)Lcpw/mods/niofs/union/UnionFileSystem;";

    private UnionFsLockPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = findTargetMethod(node);
        AbstractInsnNode monitorEnter = findUniqueOpcode(method, Opcodes.MONITORENTER);
        AbstractInsnNode syncStart = matchSyncHeader(monitorEnter);
        List<AbstractInsnNode> construction = matchConstructionBlock(monitorEnter);

        InsnList moved = new InsnList();
        for (AbstractInsnNode insn : construction) {
            method.instructions.remove(insn);
            moved.add(insn);
        }
        method.instructions.insertBefore(syncStart, moved);
        return AsmPatchSupport.write(node);
    }

    private static MethodNode findTargetMethod(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ("newFileSystemInternal".equals(method.name)
                    && (DESC_SJH2.equals(method.desc) || DESC_SJH3.equals(method.desc))) {
                return method;
            }
        }
        throw new IllegalStateException("missing UnionFileSystemProvider.newFileSystemInternal");
    }

    private static AbstractInsnNode findUniqueOpcode(MethodNode method, int opcode) {
        AbstractInsnNode match = null;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) {
                if (match != null) {
                    throw new IllegalStateException("duplicate opcode " + opcode + " in " + method.name);
                }
                match = insn;
            }
        }
        if (match == null) {
            throw new IllegalStateException("missing opcode " + opcode + " in " + method.name);
        }
        return match;
    }

    private static AbstractInsnNode matchSyncHeader(AbstractInsnNode monitorEnter) {
        AbstractInsnNode store = previousReal(monitorEnter);
        AbstractInsnNode dup = previousReal(store);
        AbstractInsnNode getField = previousReal(dup);
        AbstractInsnNode loadThis = previousReal(getField);
        if (!(store instanceof VarInsnNode storeVar && storeVar.getOpcode() == Opcodes.ASTORE && storeVar.var == 5)
                || dup == null || dup.getOpcode() != Opcodes.DUP
                || !(getField instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD
                && PROVIDER.equals(field.owner)
                && "fileSystems".equals(field.name)
                && "Ljava/util/Map;".equals(field.desc))
                || !(loadThis instanceof VarInsnNode thisVar && thisVar.getOpcode() == Opcodes.ALOAD && thisVar.var == 0)) {
            throw new IllegalStateException("unexpected synchronized(fileSystems) header in newFileSystemInternal");
        }
        return loadThis;
    }

    private static List<AbstractInsnNode> matchConstructionBlock(AbstractInsnNode monitorEnter) {
        List<AbstractInsnNode> block = new ArrayList<>(8);
        AbstractInsnNode cursor = nextReal(monitorEnter);
        if (!(cursor instanceof TypeInsnNode newInsn
                && newInsn.getOpcode() == Opcodes.NEW
                && UNION_FS.equals(newInsn.desc))) {
            throw new IllegalStateException("expected NEW UnionFileSystem after MONITORENTER");
        }
        block.add(cursor);

        cursor = nextReal(cursor);
        if (cursor == null || cursor.getOpcode() != Opcodes.DUP) {
            throw new IllegalStateException("expected DUP after NEW UnionFileSystem");
        }
        block.add(cursor);

        int[] expectedArgs = {0, 2, 1, 4};
        for (int expectedVar : expectedArgs) {
            cursor = nextReal(cursor);
            if (!(cursor instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ALOAD
                    && load.var == expectedVar)) {
                throw new IllegalStateException("expected ALOAD " + expectedVar + " for UnionFileSystem.<init>");
            }
            block.add(cursor);
        }

        cursor = nextReal(cursor);
        if (!(cursor instanceof MethodInsnNode init
                && init.getOpcode() == Opcodes.INVOKESPECIAL
                && UNION_FS.equals(init.owner)
                && "<init>".equals(init.name))) {
            throw new IllegalStateException("expected INVOKESPECIAL UnionFileSystem.<init>");
        }
        block.add(cursor);

        cursor = nextReal(cursor);
        if (!(cursor instanceof VarInsnNode store
                && store.getOpcode() == Opcodes.ASTORE
                && store.var == 6)) {
            throw new IllegalStateException("expected ASTORE 6 after UnionFileSystem.<init>");
        }
        block.add(cursor);
        return block;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode current = insn == null ? null : insn.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode current = insn == null ? null : insn.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }
}
