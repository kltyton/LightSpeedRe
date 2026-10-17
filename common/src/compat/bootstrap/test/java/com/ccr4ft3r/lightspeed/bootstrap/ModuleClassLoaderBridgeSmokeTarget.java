package com.ccr4ft3r.lightspeed.bootstrap;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks;

import java.lang.module.Configuration;
import java.util.List;

public final class ModuleClassLoaderBridgeSmokeTarget {
    private ModuleClassLoaderBridgeSmokeTarget() {
    }

    public static void main(String[] args) throws Exception {
        Class<?> loaderType = Class.forName("cpw.mods.cl.ModuleClassLoader");
        ClassLoader loader = (ClassLoader) loaderType
                .getConstructor(String.class, Configuration.class, List.class)
                .newInstance("lightspeed-bridge-smoke", Configuration.empty(), List.of());
        require(loader.getParent() == null, "fixture ModuleClassLoader does not have parent=null");
        require(loader.loadClass(BootstrapHooks.class.getName()) == BootstrapHooks.class,
                "parent-null ModuleClassLoader did not resolve the system-loader hook identity");
        try {
            loader.loadClass(AgentSmokeTarget.class.getName());
            throw new AssertionError("system-loader bridge exposed a non-hook Agent class");
        } catch (ClassNotFoundException expected) {
            // The bridge is deliberately exact: transformed code references only BootstrapHooks.
        }
        System.out.println("MODULE_CLASS_LOADER_HOOK_BRIDGE_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
