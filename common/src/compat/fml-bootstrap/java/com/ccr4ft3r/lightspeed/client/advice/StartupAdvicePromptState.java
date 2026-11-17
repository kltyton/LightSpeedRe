package com.ccr4ft3r.lightspeed.client.advice;

import java.nio.file.Path;

/**
 * Loader-independent state machine for the startup Agent consent and manual-tutorial dialog.
 */
public final class StartupAdvicePromptState {
    private final ConsentActions actions;
    private final boolean java21Recommended;
    private final boolean agentMissing;
    private boolean tutorialVisible;
    private boolean dontShowAgain;
    private Phase phase;
    private String installedLauncherDescription = "";

    public StartupAdvicePromptState(ConsentActions actions, int javaFeature, boolean agentMissing,
                                    boolean installEnabled, boolean manualConfigurationRequired) {
        this.actions = actions;
        this.java21Recommended = javaFeature < 21;
        this.agentMissing = agentMissing;
        this.dontShowAgain = actions.isSuppressed();
        if (!agentMissing) {
            this.phase = Phase.JAVA_ONLY;
            this.tutorialVisible = false;
        } else if (!installEnabled) {
            this.phase = Phase.CONSENT_REQUIRED;
            this.tutorialVisible = false;
        } else if (manualConfigurationRequired) {
            this.phase = Phase.MANUAL_REQUIRED;
            this.tutorialVisible = true;
        } else {
            this.phase = Phase.INSTALLED_NEEDS_RESTART;
            this.installedLauncherDescription = actions.installedLauncherDescription();
            this.tutorialVisible = false;
        }
    }

    public void toggleDontShowAgain() {
        dontShowAgain = !dontShowAgain;
        actions.setSuppressed(dontShowAgain);
    }

    public void writeAgentArguments() {
        actions.setSuppressed(dontShowAgain);
        InstallOutcome outcome = actions.writeAgentArguments();
        installedLauncherDescription = outcome.launcherDescription();
        if (outcome.manualRequired()) {
            phase = Phase.MANUAL_REQUIRED;
            tutorialVisible = true;
        } else {
            phase = Phase.INSTALLED_NEEDS_RESTART;
        }
    }

    public void toggleTutorial() {
        tutorialVisible = !tutorialVisible;
    }

    public void skipAndApplySuppression() {
        actions.setSuppressed(dontShowAgain);
    }

    public Phase phase() {
        return phase;
    }

    public boolean tutorialVisible() {
        return tutorialVisible;
    }

    public boolean dontShowAgain() {
        return dontShowAgain;
    }

    public boolean java21Recommended() {
        return java21Recommended;
    }

    public boolean agentMissing() {
        return agentMissing;
    }

    public boolean showWriteButton() {
        return agentMissing && (phase == Phase.CONSENT_REQUIRED || phase == Phase.MANUAL_REQUIRED);
    }

    public String installedLauncherDescription() {
        return installedLauncherDescription;
    }

    public String manualArguments() {
        return actions.manualJvmArguments();
    }

    public Path agentDirectory() {
        return actions.agentDirectory();
    }

    public enum Phase {
        CONSENT_REQUIRED,
        INSTALLED_NEEDS_RESTART,
        MANUAL_REQUIRED,
        JAVA_ONLY
    }

    public record InstallOutcome(boolean manualRequired, String launcherDescription) {
    }

    public interface ConsentActions {
        InstallOutcome writeAgentArguments();

        String manualJvmArguments();

        String installedLauncherDescription();

        Path agentDirectory();

        boolean isSuppressed();

        void setSuppressed(boolean suppressed);
    }
}
