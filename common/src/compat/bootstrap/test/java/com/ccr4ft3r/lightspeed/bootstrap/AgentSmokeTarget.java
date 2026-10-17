package com.ccr4ft3r.lightspeed.bootstrap;

public final class AgentSmokeTarget {
    private AgentSmokeTarget() {
    }

    public static void main(String[] args) {
        if (!Boolean.getBoolean("lightspeed.bootstrapAgent.active")) {
            throw new AssertionError("packaged agent did not run premain");
        }
        System.out.println("LIGHTSPEED_AGENT_PREMAIN_OK");
    }
}
