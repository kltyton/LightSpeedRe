package com.ccr4ft3r.lightspeed.client.screen;

import com.ccr4ft3r.lightspeed.client.advice.StartupAdvicePromptState;
import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
import com.ccr4ft3r.lightspeed.startup.installation.BootstrapAgentInstaller;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class StartupAdviceScreen extends Screen {
    private static final Component TITLE = Component.translatable("screen.lightspeed.startup_advice.title");
    private static final Component WRITE_AGENT = Component.translatable("screen.lightspeed.startup_advice.write_agent");
    private static final Component SKIP = Component.translatable("screen.lightspeed.startup_advice.skip");
    private static final Component CLOSE = Component.translatable("screen.lightspeed.startup_advice.close");
    private static final Component MANUAL_GUIDE = Component.translatable("screen.lightspeed.startup_advice.manual_guide");
    private static final Component BACK_OVERVIEW = Component.translatable("screen.lightspeed.startup_advice.back_overview");
    private static final Component COPY = Component.translatable("screen.lightspeed.startup_advice.copy");
    private static final Component COPIED = Component.translatable("screen.lightspeed.startup_advice.copied");
    private static final Component OPEN_DIR = Component.translatable("screen.lightspeed.startup_advice.open_dir");
    private static final Component DONT_SHOW_OFF = Component.translatable("screen.lightspeed.startup_advice.dont_show_off");
    private static final Component DONT_SHOW_ON = Component.translatable("screen.lightspeed.startup_advice.dont_show_on");
    private static final Component ARGUMENTS_LABEL = Component.translatable("screen.lightspeed.startup_advice.arguments_label");
    private static final Component ARGUMENTS_UNAVAILABLE = Component.translatable("screen.lightspeed.startup_advice.arguments_unavailable");
    private static final Component SCROLL_HINT = Component.translatable("screen.lightspeed.startup_advice.scroll_hint");
    private static final int CONTENT_TOP = 34;
    private static final int CONTENT_BOTTOM_OFFSET = 64;

    private final Screen parent;
    private final StartupAdvicePromptState state;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private Button copyButton;
    private Button toggleButton;
    private boolean copied;
    private int scrollOffset;
    private int maxScroll;

    public StartupAdviceScreen(Screen parent, int javaFeature, boolean agentMissing,
                               boolean installEnabled, boolean manualConfigurationRequired) {
        super(TITLE);
        this.parent = parent;
        this.state = new StartupAdvicePromptState(new NeoForgeConsentActions(),
                javaFeature, agentMissing, installEnabled, manualConfigurationRequired);
    }

    @Override
    protected void init() {
        clearWidgets();
        int panelWidth = panelWidth();
        int left = (this.width - panelWidth) / 2;
        int gap = 6;
        int row1Y = this.height - 54;
        int row2Y = this.height - 28;

        this.copyButton = null;
        if (state.tutorialVisible()) {
            int buttonW = (panelWidth - gap * 2) / 3;
            this.copyButton = addRenderableWidget(Button.builder(copied ? COPIED : COPY, b -> copyArguments())
                    .bounds(left, row1Y, buttonW, 20).build());
            this.copyButton.active = !state.manualArguments().isBlank();
            addRenderableWidget(Button.builder(OPEN_DIR, b -> openAgentDirectory())
                    .bounds(left + buttonW + gap, row1Y, buttonW, 20).build());
            addRenderableWidget(Button.builder(BACK_OVERVIEW, b -> toggleTutorial())
                    .bounds(left + (buttonW + gap) * 2, row1Y, panelWidth - (buttonW + gap) * 2, 20).build());
        } else if (state.showWriteButton()) {
            int buttonW = (panelWidth - gap) / 2;
            addRenderableWidget(Button.builder(WRITE_AGENT, b -> writeAgentArguments())
                    .bounds(left, row1Y, buttonW, 20).build());
            addRenderableWidget(Button.builder(MANUAL_GUIDE, b -> toggleTutorial())
                    .bounds(left + buttonW + gap, row1Y, panelWidth - buttonW - gap, 20).build());
        } else {
            int buttonW = (panelWidth - gap) / 2;
            addRenderableWidget(Button.builder(MANUAL_GUIDE, b -> toggleTutorial())
                    .bounds(left, row1Y, buttonW, 20).build());
            this.copyButton = addRenderableWidget(Button.builder(copied ? COPIED : COPY, b -> copyArguments())
                    .bounds(left + buttonW + gap, row1Y, panelWidth - buttonW - gap, 20).build());
            this.copyButton.active = !state.manualArguments().isBlank();
        }

        int toggleW = Math.min(200, Math.max(140, panelWidth - 110));
        this.toggleButton = addRenderableWidget(Button.builder(
                state.dontShowAgain() ? DONT_SHOW_ON : DONT_SHOW_OFF,
                b -> toggleDontShowAgain())
                .bounds(left, row2Y, toggleW, 20).build());
        Component dismissLabel = state.phase() == StartupAdvicePromptState.Phase.INSTALLED_NEEDS_RESTART ? CLOSE : SKIP;
        addRenderableWidget(Button.builder(dismissLabel, b -> dismiss())
                .bounds(left + toggleW + gap, row2Y, panelWidth - toggleW - gap, 20).build());

        rebuildLines(panelWidth - 16);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && mouseY >= CONTENT_TOP && mouseY <= contentBottom()) {
            scrollOffset = Mth.clamp(scrollOffset - (int) (scrollY * 16), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int panelWidth = panelWidth();
        int left = (this.width - panelWidth) / 2;
        int bottom = contentBottom();
        graphics.fill(left - 6, 8, left + panelWidth + 6, CONTENT_TOP - 2, 0x900B1018);
        graphics.fill(left, CONTENT_TOP, left + panelWidth, bottom, 0xB80B1018);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);

        graphics.enableScissor(left + 4, CONTENT_TOP + 4, left + panelWidth - 4, bottom - 4);
        int y = CONTENT_TOP + 8 - scrollOffset;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(this.font, line, left + 8, y, 0xEAEAEA);
            y += 11;
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            graphics.drawCenteredString(this.font, SCROLL_HINT, this.width / 2, bottom - 12, 0xA8B8C8);
        }
    }

    @Override
    public void onClose() {
        dismiss();
    }

    private void writeAgentArguments() {
        state.writeAgentArguments();
        copied = false;
        scrollOffset = 0;
        init();
    }

    private void toggleTutorial() {
        state.toggleTutorial();
        scrollOffset = 0;
        init();
    }

    private void toggleDontShowAgain() {
        state.toggleDontShowAgain();
        if (toggleButton != null) {
            toggleButton.setMessage(state.dontShowAgain() ? DONT_SHOW_ON : DONT_SHOW_OFF);
        }
    }

    private void copyArguments() {
        String arguments = state.manualArguments();
        if (minecraft == null || arguments.isBlank()) {
            return;
        }
        minecraft.keyboardHandler.setClipboard(arguments);
        copied = true;
        if (copyButton != null) {
            copyButton.setMessage(COPIED);
        }
    }

    private void openAgentDirectory() {
        Path dir = state.agentDirectory();
        try {
            Files.createDirectories(dir);
            Util.getPlatform().openFile(dir.toFile());
        } catch (Exception ignored) {
        }
    }

    private void dismiss() {
        state.skipAndApplySuppression();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private int panelWidth() {
        return Math.min(460, Math.max(260, this.width - 24));
    }

    private int contentBottom() {
        return Math.max(CONTENT_TOP + 40, this.height - CONTENT_BOTTOM_OFFSET);
    }

    private void rebuildLines(int textWidth) {
        lines.clear();
        if (state.tutorialVisible()) {
            addWrapped(Component.translatable("screen.lightspeed.startup_advice.tutorial_title"), textWidth);
            lines.add(FormattedCharSequence.EMPTY);
            if (state.phase() == StartupAdvicePromptState.Phase.MANUAL_REQUIRED) {
                addWrapped(Component.translatable("screen.lightspeed.startup_advice.message.manual"), textWidth);
                lines.add(FormattedCharSequence.EMPTY);
            }
            addWrapped(Component.translatable("screen.lightspeed.startup_advice.tutorial_steps",
                    state.agentDirectory().toString()), textWidth);
            appendArgumentsSection(textWidth);
        } else {
            switch (state.phase()) {
                case CONSENT_REQUIRED -> {
                    addWrapped(Component.translatable("screen.lightspeed.startup_advice.message.consent"), textWidth);
                    lines.add(FormattedCharSequence.EMPTY);
                    addWrapped(Component.translatable("screen.lightspeed.startup_advice.message.supported_list"), textWidth);
                    if (state.java21Recommended()) {
                        lines.add(FormattedCharSequence.EMPTY);
                        addWrapped(Component.translatable("screen.lightspeed.startup_advice.message.java_note",
                                Runtime.version().feature()), textWidth);
                    }
                }
                case INSTALLED_NEEDS_RESTART -> {
                    addWrapped(Component.translatable("screen.lightspeed.startup_advice.message.installed",
                            state.installedLauncherDescription()), textWidth);
                    appendArgumentsSection(textWidth);
                }
                case MANUAL_REQUIRED -> {
                    addWrapped(Component.translatable("screen.lightspeed.startup_advice.message.manual"), textWidth);
                    appendArgumentsSection(textWidth);
                }
                case JAVA_ONLY -> addWrapped(Component.translatable("screen.lightspeed.startup_advice.message.java_only"), textWidth);
            }
        }
        int visibleHeight = contentBottom() - CONTENT_TOP - 16;
        maxScroll = Math.max(0, lines.size() * 11 - visibleHeight);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
    }

    private void appendArgumentsSection(int textWidth) {
        lines.add(FormattedCharSequence.EMPTY);
        lines.addAll(this.font.split(ARGUMENTS_LABEL, textWidth));
        String arguments = state.manualArguments();
        addWrapped(arguments.isBlank() ? ARGUMENTS_UNAVAILABLE : Component.literal(arguments), textWidth);
    }

    private void addWrapped(Component component, int textWidth) {
        for (String paragraph : component.getString().split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add(FormattedCharSequence.EMPTY);
            } else {
                lines.addAll(this.font.split(Component.literal(paragraph), textWidth));
            }
        }
    }

    private static final class NeoForgeConsentActions implements StartupAdvicePromptState.ConsentActions {
        @Override
        public StartupAdvicePromptState.InstallOutcome writeAgentArguments() {
            LightspeedConfig.COMMON.installBootstrapAgent.set(true);
            LightspeedConfig.COMMON.installBootstrapAgent.save();
            BootstrapAgentInstaller.installFromUserConsent();
            return new StartupAdvicePromptState.InstallOutcome(
                    BootstrapAgentInstaller.manualConfigurationRequired(),
                    BootstrapAgentInstaller.installedLauncherDescription());
        }

        @Override
        public String manualJvmArguments() {
            return BootstrapAgentInstaller.manualJvmArguments();
        }

        @Override
        public String installedLauncherDescription() {
            return BootstrapAgentInstaller.installedLauncherDescription();
        }

        @Override
        public Path agentDirectory() {
            return BootstrapAgentInstaller.agentDirectory();
        }

        @Override
        public boolean isSuppressed() {
            return LightspeedConfig.COMMON.suppressStartupRecommendations.get();
        }

        @Override
        public void setSuppressed(boolean suppressed) {
            if (LightspeedConfig.COMMON.suppressStartupRecommendations.get() != suppressed) {
                LightspeedConfig.COMMON.suppressStartupRecommendations.set(suppressed);
                LightspeedConfig.COMMON.suppressStartupRecommendations.save();
            }
        }
    }
}
