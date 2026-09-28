package com.ccr4ft3r.lightspeed.bootstrap;

import com.sun.tools.attach.VirtualMachine;

/** Runs in a short-lived helper JVM because HotSpot disables direct self-attach by default. */
public final class AgentAttachHelper {
    private AgentAttachHelper() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected <pid> <agent-jar>");
        }
        VirtualMachine target = VirtualMachine.attach(arguments[0]);
        try {
            target.loadAgent(arguments[1], "dynamic");
        } finally {
            target.detach();
        }
        System.out.println("LIGHTSPEED_DYNAMIC_ATTACH_OK");
    }
}
