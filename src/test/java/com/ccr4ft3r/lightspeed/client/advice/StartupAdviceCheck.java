package com.ccr4ft3r.lightspeed.client.advice;

public final class StartupAdviceCheck {
    private StartupAdviceCheck() {
    }

    public static void main(String[] args) {
        requireAdvice(17, null, null, true, false, true, true, true, false, true);
        requireAdvice(17, "false", "false", false, true, true, true, false, true, true);
        requireAdvice(20, "true", "false", false, true, true, false, false, true, true);
        requireAdvice(21, "false", "false", true, false, false, true, true, false, true);
        requireAdvice(21, "false", "false", false, false, false, true, false, false, true);
        requireAdvice(21, "false", "false", false, true, false, true, false, true, false);
        requireAdvice(21, "true", "true", false, true, false, true, false, true, false);
        requireAdvice(21, "true", "true", true, false, false, true, true, false, true);
        requireAdvice(21, "TRUE", "false", true, false, false, false, true, false, false);
        requireAdvice(22, "true", "false", false, true, false, false, false, true, false);
        System.out.println("STARTUP_ADVICE_OK");
    }

    private static void requireAdvice(int javaFeature, String agentActiveProperty, String dynamicProperty,
                                      boolean manualConfigurationRequired,
                                      boolean launcherReadyForNextLaunch,
                                      boolean java21Recommended, boolean agentMissing,
                                      boolean expectedManualConfigurationRequired,
                                      boolean expectedLauncherReadyForNextLaunch, boolean shouldShow) {
        StartupAdvice.Advice advice = StartupAdvice.select(
                javaFeature, agentActiveProperty, dynamicProperty, manualConfigurationRequired,
                launcherReadyForNextLaunch);
        if (advice.java21Recommended() != java21Recommended
                || advice.agentMissing() != agentMissing
                || advice.manualConfigurationRequired() != expectedManualConfigurationRequired
                || advice.launcherReadyForNextLaunch() != expectedLauncherReadyForNextLaunch
                || advice.shouldShow() != shouldShow) {
            throw new AssertionError("Unexpected advice for Java " + javaFeature
                    + " and Agent property " + agentActiveProperty + ": " + advice);
        }
    }
}
