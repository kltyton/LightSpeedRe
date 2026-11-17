package com.ccr4ft3r.lightspeed.bootstrap.packaging;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import java.nio.file.Files;
import java.nio.file.Path;

/** Produces a service module that keeps game packages private to its early layer. */
public final class EarlyServiceModule {
    private EarlyServiceModule() { }

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        boolean forge = arguments[1].equals("forge");
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor module = writer.visitModule("com.ccr4ft3r.lightspeed.early", 0, arguments[2]);
        module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        for (String dependency : new String[]{"java.management", "java.instrument", "cpw.mods.modlauncher",
                "cpw.mods.securejarhandler", "org.objectweb.asm", "org.objectweb.asm.tree",
                "org.slf4j", "logging", "com.sun.jna",
                "com.electronwill.nightconfig.core", forge ? "fmlloader" : "fml_loader"}) {
            module.visitRequire(dependency, 0, null);
        }
        if (forge) module.visitRequire("net.minecraftforge.forgespi", 0, null);
        module.visitOpen("com/ccr4ft3r/lightspeed/startup/bootstrap/path", 0, "com.sun.jna");
        module.visitProvide("cpw/mods/modlauncher/api/ITransformationService",
                "com/ccr4ft3r/lightspeed/startup/bootstrap/BootstrapTransformationService");
        module.visitProvide(forge ? "net/minecraftforge/forgespi/locating/IModLocator"
                        : "net/neoforged/neoforgespi/locating/IModFileCandidateLocator",
                "com/ccr4ft3r/lightspeed/startup/bootstrap/BootstrapModLocator");
        module.visitEnd();
        writer.visitEnd();
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
