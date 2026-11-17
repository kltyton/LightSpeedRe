package com.ccr4ft3r.lightspeed.client.advice;

public final class StartupAdvice {
    private StartupAdvice() {
    }

    public static Advice select(int javaFeature, String agentActiveProperty, String dynamicProperty,
                                boolean manualConfigurationRequired, boolean launcherReadyForNextLaunch) {
        boolean java21Recommended = javaFeature < 21;
        boolean agentPremainActive = "true".equalsIgnoreCase(agentActiveProperty)
                && !"true".equalsIgnoreCase(dynamicProperty);
        boolean agentMissing = !agentPremainActive;
        boolean shouldShow = java21Recommended
                || (agentMissing && (manualConfigurationRequired || !launcherReadyForNextLaunch));
        return new Advice(java21Recommended, agentMissing, manualConfigurationRequired,
                launcherReadyForNextLaunch, shouldShow);
    }

    public static boolean shouldShowPrompt(int javaFeature, String agentActiveProperty, String dynamicProperty,
                                           boolean installBootstrapAgentEnabled, boolean manualConfigurationRequired,
                                           boolean launcherReadyForNextLaunch, boolean suppressPrompt) {
        if (suppressPrompt) {
            return false;
        }
        boolean agentPremainActive = "true".equalsIgnoreCase(agentActiveProperty)
                && !"true".equalsIgnoreCase(dynamicProperty);
        if (!agentPremainActive && !installBootstrapAgentEnabled) {
            return true;
        }
        return select(javaFeature, agentActiveProperty, dynamicProperty,
                manualConfigurationRequired, launcherReadyForNextLaunch).shouldShow();
    }

    public record Advice(boolean java21Recommended, boolean agentMissing, boolean manualConfigurationRequired,
                         boolean launcherReadyForNextLaunch, boolean shouldShow) {
    }
}
