package com.ccr4ft3r.lightspeed.client.screen;

import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class StartupAdviceScreen extends Screen {
    private static final Component TITLE = Component.translatable("screen.lightspeed.startup_advice.title");
    private static final Component LAUNCHER_PATHS =
            Component.translatable("screen.lightspeed.startup_advice.launcher_paths");
    private static final Component ARGUMENTS_LABEL =
            Component.translatable("screen.lightspeed.startup_advice.arguments_label");
    private static final Component COPY = Component.translatable("screen.lightspeed.startup_advice.copy");
    private static final Component COPIED = Component.translatable("screen.lightspeed.startup_advice.copied");
    private static final Component DONE = Component.translatable("screen.lightspeed.startup_advice.done");
    private static final Component SUPPRESS = Component.translatable("screen.lightspeed.startup_advice.suppress");

    private final Screen parent;
    private final boolean java21Recommended;
    private final boolean agentMissing;
    private final boolean manualConfigurationRequired;
    private final String jvmArguments;
    private int scrollOffset;
    private int maxScroll;

    public StartupAdviceScreen(Screen parent, boolean java21Recommended, boolean agentMissing,
                               boolean manualConfigurationRequired, String jvmArguments) {
        super(TITLE);
        this.parent = parent;
        this.java21Recommended = java21Recommended;
        this.agentMissing = agentMissing;
        this.manualConfigurationRequired = manualConfigurationRequired;
        this.jvmArguments = jvmArguments;
    }

    @Override
    protected void init() {
        int horizontalMargin = Math.max(12, this.width / 12);
        int contentWidth = this.width - horizontalMargin * 2;
        int bottomButtonY = this.height - 28;
        int gap = 8;
        int buttonWidth = (contentWidth - gap) / 2;

        if (manualConfigurationRequired && !jvmArguments.isBlank()) {
            addRenderableWidget(Button.builder(COPY, button -> {
                        Minecraft.getInstance().keyboardHandler.setClipboard(jvmArguments);
                        button.setMessage(COPIED);
                    })
                    .bounds(this.width / 2 - 100, this.height - 54, 200, 20)
                    .build());
        }

        addRenderableWidget(Button.builder(DONE, button -> onClose())
                .bounds(horizontalMargin, bottomButtonY, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(SUPPRESS, button -> suppressAndClose())
                .bounds(horizontalMargin + buttonWidth + gap, bottomButtonY, buttonWidth, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);

        int horizontalMargin = Math.max(12, this.width / 12);
        int contentWidth = this.width - horizontalMargin * 2;
        int viewportTop = 34;
        int viewportBottom = this.height - 60;
        graphics.enableScissor(horizontalMargin, viewportTop,
                horizontalMargin + contentWidth, viewportBottom);
        int y = drawWrapped(graphics, adviceMessage(), horizontalMargin,
                40 - scrollOffset, contentWidth, 0xE0E0E0);
        if (manualConfigurationRequired) {
            y = drawWrapped(graphics, LAUNCHER_PATHS, horizontalMargin, y + 8, contentWidth, 0xA0A0A0);
            y = drawWrapped(graphics, ARGUMENTS_LABEL, horizontalMargin, y + 8, contentWidth, 0xFFD966);
            if (!jvmArguments.isBlank()) {
                y = drawWrapped(graphics, Component.literal(jvmArguments),
                        horizontalMargin, y + 2, contentWidth, 0xFFFFFF);
            } else {
                y = drawWrapped(graphics,
                        Component.translatable("screen.lightspeed.startup_advice.arguments_unavailable"),
                        horizontalMargin, y + 8, contentWidth, 0xFF8080);
            }
        }
        graphics.disableScissor();
        int contentHeight = y + scrollOffset - 40;
        maxScroll = Math.max(0, contentHeight - (viewportBottom - 40));
        if (maxScroll > 0) {
            graphics.drawString(this.font,
                    Component.translatable("screen.lightspeed.startup_advice.scroll_hint"),
                    horizontalMargin, viewportBottom - this.font.lineHeight, 0x909090, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll > 0 && delta != 0) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(delta) * 18));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int drawWrapped(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        List<FormattedCharSequence> lines = this.font.split(text, width);
        for (FormattedCharSequence line : lines) {
            graphics.drawString(this.font, line, x, y, color, false);
            y += this.font.lineHeight + 2;
        }
        return y;
    }

    private void suppressAndClose() {
        LightspeedConfig.COMMON.suppressStartupRecommendations.set(true);
        LightspeedConfig.COMMON.suppressStartupRecommendations.save();
        onClose();
    }

    private Component adviceMessage() {
        String reason;
        if (java21Recommended && agentMissing) {
            reason = manualConfigurationRequired ? "java_and_agent" : "java_and_agent_restart";
        } else if (java21Recommended) {
            reason = "java";
        } else {
            reason = manualConfigurationRequired ? "agent" : "agent_restart";
        }
        return Component.translatable("screen.lightspeed.startup_advice.message." + reason);
    }
}
