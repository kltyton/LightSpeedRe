package com.ccr4ft3r.lightspeed.client.advice;

public final class StartupAdvice {
    private StartupAdvice() {
    }

    public static Advice select(int javaFeature, String agentActiveProperty, String dynamicProperty,
                                boolean manualConfigurationRequired, boolean launcherReadyForNextLaunch) {
        boolean premainActive = Boolean.parseBoolean(agentActiveProperty)
                && !Boolean.parseBoolean(dynamicProperty);
        return new Advice(javaFeature < 21, !premainActive, manualConfigurationRequired,
                launcherReadyForNextLaunch);
    }

    public record Advice(boolean java21Recommended, boolean agentMissing,
                         boolean manualConfigurationRequired, boolean launcherReadyForNextLaunch) {
        public boolean shouldShow() {
            return java21Recommended || agentMissing && !launcherReadyForNextLaunch;
        }
    }
}
