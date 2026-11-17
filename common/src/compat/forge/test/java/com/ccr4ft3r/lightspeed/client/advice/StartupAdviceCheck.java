package com.ccr4ft3r.lightspeed.client.advice;

import java.nio.file.Path;

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
        checkPromptConsentFlow();
        System.out.println("STARTUP_ADVICE_OK");
    }

    private static void checkPromptConsentFlow() {
        if (!StartupAdvice.shouldShowPrompt(21, "false", "false", false, false, true, false)) {
            throw new AssertionError("First-launch unconsented prompt must be shown when Agent is inactive");
        }
        if (StartupAdvice.shouldShowPrompt(21, "false", "false", false, false, false, true)) {
            throw new AssertionError("Suppressed prompt must stay hidden");
        }
        if (StartupAdvice.shouldShowPrompt(21, "true", "false", true, false, true, false)) {
            throw new AssertionError("Active Java 21 premain launch must not show prompt");
        }

        final boolean[] suppressed = {false};
        final boolean[] manual = {false};
        StartupAdvicePromptState.ConsentActions actions = new StartupAdvicePromptState.ConsentActions() {
            @Override
            public StartupAdvicePromptState.InstallOutcome writeAgentArguments() {
                return new StartupAdvicePromptState.InstallOutcome(manual[0], manual[0] ? "unsupported" : "PCL");
            }

            @Override
            public String manualJvmArguments() {
                return "-javaagent:test.jar";
            }

            @Override
            public String installedLauncherDescription() {
                return "PCL";
            }

            @Override
            public Path agentDirectory() {
                return Path.of(".lightspeed/bootstrap");
            }

            @Override
            public boolean isSuppressed() {
                return suppressed[0];
            }

            @Override
            public void setSuppressed(boolean value) {
                suppressed[0] = value;
            }
        };

        StartupAdvicePromptState state = new StartupAdvicePromptState(actions, 21, true, false, false);
        if (state.phase() != StartupAdvicePromptState.Phase.CONSENT_REQUIRED || !state.showWriteButton()) {
            throw new AssertionError("Expected initial CONSENT_REQUIRED phase");
        }
        state.writeAgentArguments();
        if (state.phase() != StartupAdvicePromptState.Phase.INSTALLED_NEEDS_RESTART || state.showWriteButton()) {
            throw new AssertionError("Expected INSTALLED_NEEDS_RESTART after successful write");
        }

        manual[0] = true;
        StartupAdvicePromptState obscure = new StartupAdvicePromptState(actions, 21, true, false, false);
        obscure.toggleDontShowAgain();
        obscure.writeAgentArguments();
        if (obscure.phase() != StartupAdvicePromptState.Phase.MANUAL_REQUIRED || !obscure.tutorialVisible() || !suppressed[0]) {
            throw new AssertionError("Unsupported launcher must open manual tutorial and preserve suppression");
        }
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
