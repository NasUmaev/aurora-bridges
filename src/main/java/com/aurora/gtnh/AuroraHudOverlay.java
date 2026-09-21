package com.aurora.gtnh;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Shows short-lived Aurora replies while her chat screen is closed. */
final class AuroraHudOverlay {

    private static final long DISPLAY_MILLIS = 10_000L;
    private static final int MAX_MESSAGES = 3;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private List<ChatLine> vanillaLines = Collections.emptyList();
    private boolean vanillaHistoryResolved;

    void add(String text) {
        entries.addLast(new Entry(text, System.currentTimeMillis()));
        while (entries.size() > MAX_MESSAGES) entries.removeFirst();
    }

    void clear() {
        entries.clear();
    }

    void render(Minecraft minecraft, ScaledResolution resolution) {
        if (minecraft.currentScreen instanceof GuiChat || minecraft.thePlayer == null) return;

        long now = System.currentTimeMillis();
        while (!entries.isEmpty() && now - entries.peekFirst().createdAt >= DISPLAY_MILLIS) entries.removeFirst();
        if (entries.isEmpty()) return;

        int maxWidth = Math.min(360, resolution.getScaledWidth() * 3 / 5);
        int vanillaHeight = visibleVanillaChatLines(minecraft)
            * MathHelper.ceiling_float_int(9.0F * minecraft.gameSettings.chatScale);
        int y = resolution.getScaledHeight() - 48 - vanillaHeight;
        if (vanillaHeight > 0) y -= 4;
        for (Entry entry : entriesDescending()) {
            List<String> lines = minecraft.fontRenderer
                .listFormattedStringToWidth("§d[Аврора] §f" + entry.text, maxWidth);
            for (int index = lines.size() - 1; index >= 0; index--) {
                String line = lines.get(index);
                int width = minecraft.fontRenderer.getStringWidth(line);
                Gui.drawRect(2, y - 1, width + 8, y + 9, 0x78000000);
                minecraft.fontRenderer.drawStringWithShadow(line, 5, y, 0xFFFFFFFF);
                y -= 10;
            }
            y -= 2;
            if (y < 8) break;
        }
    }

    private Iterable<Entry> entriesDescending() {
        return entries::descendingIterator;
    }

    @SuppressWarnings("unchecked")
    private int visibleVanillaChatLines(Minecraft minecraft) {
        if (minecraft.gameSettings.chatVisibility == EntityPlayer.EnumChatVisibility.HIDDEN) return 0;
        GuiNewChat chat = minecraft.ingameGUI.getChatGUI();
        if (!vanillaHistoryResolved) {
            vanillaHistoryResolved = true;
            try {
                vanillaLines = ReflectionHelper.getPrivateValue(GuiNewChat.class, chat, "field_146253_i");
            } catch (RuntimeException exception) {
                vanillaLines = Collections.emptyList();
                AuroraBridgeMod.LOG.warn("Could not position Aurora above vanilla chat", exception);
            }
        }

        int updateCounter = minecraft.ingameGUI.getUpdateCounter();
        int limit = Math.min(chat.func_146232_i(), vanillaLines.size());
        float opacity = minecraft.gameSettings.chatOpacity * 0.9F + 0.1F;
        int visible = 0;
        for (int index = 0; index < limit; index++) {
            int age = updateCounter - vanillaLines.get(index)
                .getUpdatedCounter();
            if (age >= 200) continue;
            double fade = 1.0D - (double) age / 200.0D;
            fade = MathHelper.clamp_double(fade * 10.0D, 0.0D, 1.0D);
            int alpha = (int) (255.0D * fade * fade * opacity);
            if (alpha > 3) visible++;
        }
        return visible;
    }

    private static final class Entry {

        private final String text;
        private final long createdAt;

        private Entry(String text, long createdAt) {
            this.text = text;
            this.createdAt = createdAt;
        }
    }
}
