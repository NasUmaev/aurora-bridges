package com.aurora.gtnh;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

public final class GuiAuroraSetup extends GuiScreen {

    private final GuiScreen parent;
    private final AuroraRuntimeManager runtime;
    private final KnowledgeRepository knowledge;
    private volatile String status = "Готова к установке";
    private volatile double progress;
    private volatile boolean installing;
    private volatile boolean complete;
    private volatile String error = "";

    public GuiAuroraSetup(GuiScreen parent, AuroraRuntimeManager runtime, KnowledgeRepository knowledge) {
        this.parent = parent;
        this.runtime = runtime;
        this.knowledge = knowledge;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        if (complete) {
            buttonList.add(new GuiButton(2, width / 2 - 100, height / 2 + 58, "Продолжить"));
        } else {
            buttonList.add(new GuiButton(0, width / 2 - 100, height / 2 + 58, "Установить Аврору"));
            buttonList.add(new GuiButton(1, width / 2 - 100, height / 2 + 84, "Не сейчас"));
            setButtonsEnabled(!installing);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1 || button.id == 2) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id != 0 || installing) return;

        installing = true;
        error = "";
        setButtonsEnabled(false);
        Thread worker = new Thread(() -> {
            try {
                new AuroraInstaller(runtime, knowledge).install((message, value) -> {
                    status = message;
                    progress = Math.max(0.0D, Math.min(1.0D, value));
                });
                complete = true;
                status = "Аврора готова и уже проснулась ✨";
            } catch (Exception exception) {
                AuroraBridgeMod.LOG.error("Aurora installation failed", exception);
                error = exception.getMessage() == null ? exception.toString() : exception.getMessage();
                status = "Установка не завершена";
            } finally {
                installing = false;
            }
        }, "Aurora-Installer");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void updateScreen() {
        if (!installing && ((complete && buttonList.size() != 1) || (!complete && buttonList.size() != 2))) {
            initGui();
        } else if (!installing && !complete) {
            setButtonsEnabled(true);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Аврора внутри GT New Horizons", width / 2, height / 2 - 82, 0xE26BFF);
        drawCenteredString(
            fontRendererObj,
            "Установщик подготовит Ollama, модель и внешний профиль знаний (~5 ГБ).",
            width / 2,
            height / 2 - 55,
            0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            "Данные останутся на этом Mac в папке minecraft/aurora.",
            width / 2,
            height / 2 - 40,
            0xAAAAAA);
        drawCenteredString(fontRendererObj, status, width / 2, height / 2 - 12, complete ? 0x72FF8B : 0xFFFFFF);

        int barLeft = width / 2 - 120;
        int barTop = height / 2 + 10;
        drawRect(barLeft, barTop, barLeft + 240, barTop + 12, 0xFF202020);
        drawRect(barLeft + 1, barTop + 1, barLeft + 1 + (int) (238.0D * progress), barTop + 11, 0xFFE052FF);
        if (!error.isEmpty()) {
            List<String> lines = fontRendererObj.listFormattedStringToWidth("Ошибка: " + error, 420);
            int y = height / 2 + 34;
            for (String line : lines) {
                drawCenteredString(fontRendererObj, line, width / 2, y, 0xFF7777);
                y += 10;
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char character, int keyCode) {
        if (!installing) super.keyTyped(character, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void setButtonsEnabled(boolean enabled) {
        for (Object item : buttonList) ((GuiButton) item).enabled = enabled;
    }
}
