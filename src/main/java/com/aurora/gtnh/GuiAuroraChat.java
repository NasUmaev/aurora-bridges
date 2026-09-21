package com.aurora.gtnh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** A Minecraft-native, two-tab chat: public game chat and the private Aurora channel. */
public final class GuiAuroraChat extends GuiChat {

    public enum Tab {
        GAME,
        AURORA
    }

    private static Tab lastTab = Tab.GAME;
    private final String initialText;
    private Tab activeTab;
    private int panelLeft;
    private int panelRight;
    private int panelTop;
    private int contentTop;
    private int contentBottom;
    private int inputBarTop;
    private int gameScroll;
    private int auroraScroll;
    private List<ChatLine> gameLines = Collections.emptyList();
    private boolean gameHistoryAvailable;
    private List<String> cachedAuroraLines = Collections.emptyList();
    private long cachedConversationRevision = -1L;
    private int cachedTextWidth = -1;
    private boolean cachedThinking;

    public GuiAuroraChat(String initialText, Tab initialTab) {
        super(initialText);
        this.initialText = initialText;
        this.activeTab = initialTab;
    }

    @Override
    public void initGui() {
        super.initGui();
        panelLeft = 0;
        panelRight = Math.min(width - 6, Math.max(310, width * 56 / 100));
        panelTop = Math.max(16, height * 36 / 100);
        contentTop = panelTop + 19;
        inputBarTop = height - 29;
        contentBottom = inputBarTop - 2;
        inputField = new GuiTextField(fontRendererObj, panelLeft + 7, height - 16, panelRight - panelLeft - 14, 12);
        inputField.setEnableBackgroundDrawing(false);
        inputField.setFocused(true);
        inputField.setCanLoseFocus(false);
        inputField.setMaxStringLength(activeTab == Tab.AURORA ? 500 : 100);
        inputField.setText(initialText);
        resolveGameHistory();
        if (activeTab == Tab.AURORA) AuroraConversation.markRead();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(panelLeft, panelTop, panelRight, height, 0xB8000000);
        drawRect(panelLeft, panelTop, panelRight, contentTop, 0xD0000000);

        int middle = panelLeft + (panelRight - panelLeft) / 2;
        drawTab(panelLeft, middle, "Игровой чат", activeTab == Tab.GAME);
        String auroraTitle = AuroraConversation.getUnread() > 0 && activeTab != Tab.AURORA ? "Аврора  •" : "Аврора";
        drawTab(middle, panelRight, auroraTitle, activeTab == Tab.AURORA);

        if (activeTab == Tab.GAME) {
            drawGameMessages();
        } else {
            AuroraConversation.markRead();
            drawAuroraMessages();
        }

        drawRect(panelLeft, inputBarTop, panelRight, height, 0xA8000000);
        String hint = activeTab == Tab.AURORA ? "Написать Авроре" : "Сообщение или команда";
        fontRendererObj.drawString(hint, panelLeft + 7, inputBarTop + 3, 0x777777);
        inputField.drawTextBox();
    }

    private void drawTab(int left, int right, String title, boolean selected) {
        int color = selected ? 0xFFE05CFF : 0xFFAAAAAA;
        if (selected) drawRect(left, contentTop - 2, right, contentTop, 0xFFE05CFF);
        int textX = left + (right - left - fontRendererObj.getStringWidth(title)) / 2;
        fontRendererObj.drawStringWithShadow(title, textX, panelTop + 5, color);
    }

    private void drawGameMessages() {
        if (!gameHistoryAvailable) {
            fontRendererObj.drawString("История игрового чата недоступна", panelLeft + 6, contentTop + 6, 0xAAAAAA);
            return;
        }
        int y = contentBottom - 9;
        int visible = Math.max(1, (contentBottom - contentTop - 4) / 10);
        int drawn = 0;
        for (int index = gameScroll; index < gameLines.size() && drawn < visible; index++, drawn++) {
            String text = gameLines.get(index)
                .func_151461_a()
                .getFormattedText();
            fontRendererObj.drawStringWithShadow(text, panelLeft + 6, y, 0xFFFFFF);
            y -= 10;
        }
    }

    private void drawAuroraMessages() {
        refreshAuroraLines();

        int y = contentBottom - 9;
        int visible = Math.max(1, (contentBottom - contentTop - 4) / 10);
        int start = cachedAuroraLines.size() - 1 - auroraScroll;
        for (int index = start, drawn = 0; index >= 0 && drawn < visible; index--, drawn++) {
            fontRendererObj.drawStringWithShadow(cachedAuroraLines.get(index), panelLeft + 6, y, 0xFFFFFF);
            y -= 10;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_TAB && (activeTab == Tab.AURORA || inputField.getText()
            .isEmpty())) {
            switchTab(activeTab == Tab.GAME ? Tab.AURORA : Tab.GAME);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            String text = inputField.getText()
                .trim();
            if (text.isEmpty()) return;
            if (activeTab == Tab.AURORA) {
                AuroraClient.sendPrompt(text);
                inputField.setText("");
                auroraScroll = 0;
                return;
            }
        }
        if (activeTab == Tab.GAME) {
            super.keyTyped(typedChar, keyCode);
        } else {
            inputField.textboxKeyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 0 && mouseX >= panelLeft
            && mouseX < panelRight
            && mouseY >= panelTop
            && mouseY <= contentTop) {
            int middle = panelLeft + (panelRight - panelLeft) / 2;
            switchTab(mouseX < middle ? Tab.GAME : Tab.AURORA);
            return;
        }
        inputField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() {
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            super.handleMouseInput();
            return;
        }
        int direction = wheel < 0 ? 3 : -3;
        if (activeTab == Tab.AURORA) {
            refreshAuroraLines();
            int max = Math.max(0, cachedAuroraLines.size() - 1);
            auroraScroll = clamp(auroraScroll + direction, 0, max);
        } else {
            int max = Math.max(0, gameLines.size() - 1);
            gameScroll = clamp(gameScroll + direction, 0, max);
        }
    }

    private void switchTab(Tab tab) {
        activeTab = tab;
        lastTab = tab;
        inputField.setMaxStringLength(tab == Tab.AURORA ? 500 : 100);
        if (tab == Tab.AURORA) AuroraConversation.markRead();
    }

    @SuppressWarnings("unchecked")
    private void resolveGameHistory() {
        try {
            GuiNewChat chat = mc.ingameGUI.getChatGUI();
            gameLines = ReflectionHelper.getPrivateValue(GuiNewChat.class, chat, "field_146253_i");
            gameHistoryAvailable = true;
        } catch (RuntimeException exception) {
            gameLines = Collections.emptyList();
            gameHistoryAvailable = false;
            AuroraBridgeMod.LOG.warn("Could not access Minecraft chat history", exception);
        }
    }

    private void refreshAuroraLines() {
        long revision = AuroraConversation.getRevision();
        int textWidth = panelRight - panelLeft - 14;
        boolean thinking = AuroraClient.isThinking();
        if (revision == cachedConversationRevision && textWidth == cachedTextWidth && thinking == cachedThinking)
            return;

        List<String> lines = new ArrayList<>();
        for (AuroraConversation.Message message : AuroraConversation.snapshot()) {
            String prefix = message.fromAurora ? EnumChatFormatting.LIGHT_PURPLE + "[Аврора] "
                : EnumChatFormatting.GRAY + "[Вы] ";
            String color = message.fromAurora ? EnumChatFormatting.WHITE.toString()
                : EnumChatFormatting.GRAY.toString();
            lines.addAll(fontRendererObj.listFormattedStringToWidth(prefix + color + message.text, textWidth));
        }
        if (thinking) {
            lines.add(EnumChatFormatting.LIGHT_PURPLE + "[Аврора] " + EnumChatFormatting.DARK_GRAY + "думает...");
        }
        cachedAuroraLines = lines;
        cachedConversationRevision = revision;
        cachedTextWidth = textWidth;
        cachedThinking = thinking;
        auroraScroll = clamp(auroraScroll, 0, Math.max(0, lines.size() - 1));
    }

    public static Tab getLastTab() {
        return lastTab;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
