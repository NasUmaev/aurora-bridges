package com.aurora.gtnh;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.google.gson.JsonObject;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

public final class AuroraClient {

    private static final AuroraClient INSTANCE = new AuroraClient();
    private static final ExecutorService AI_IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Aurora-AI");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService MAINTENANCE_IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Aurora-Maintenance");
        thread.setDaemon(true);
        return thread;
    });

    private final Queue<String> replies = new ConcurrentLinkedQueue<>();
    private final Deque<String> recentChat = new ArrayDeque<>();
    private final AuroraRuntimeManager runtime = new AuroraRuntimeManager();
    private final KnowledgeRepository knowledge = new KnowledgeRepository();
    private final KnowledgeCatalogUpdater knowledgeUpdater = new KnowledgeCatalogUpdater();
    private final OllamaClient ollama = new OllamaClient(knowledge);
    private final AuroraHudOverlay overlay = new AuroraHudOverlay();
    private final KeyBinding auroraChatKey = new KeyBinding("Открыть чат Авроры", Keyboard.KEY_V, "Аврора");
    private final AtomicBoolean requestInFlight = new AtomicBoolean();
    private boolean inWorld;
    private boolean readyNoticeShown;
    private boolean installNoticeShown;
    private boolean setupScreenOffered;
    private volatile long worldSession;

    private AuroraClient() {}

    public static void start() {
        if (!BridgeConfig.enabled) {
            AuroraBridgeMod.LOG.info("Aurora Companion is disabled in config");
            return;
        }
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
        ClientCommandHandler.instance.registerCommand(new AuroraCommand());
        ClientRegistry.registerKeyBinding(INSTANCE.auroraChatKey);
        MAINTENANCE_IO.execute(() -> {
            INSTANCE.knowledge.reload();
            INSTANCE.runtime.ensureRunning();
            if (!INSTANCE.knowledge.needsProfileInstall()) INSTANCE.updateKnowledgeFromCatalog(false);
        });
        AuroraBridgeMod.LOG.info("Aurora initialized for direct Ollama access at {}", BridgeConfig.ollamaUrl);
    }

    @SubscribeEvent
    public void onIncomingChat(ClientChatReceivedEvent event) {
        if (event.message == null) return;
        if (!BridgeConfig.captureIncomingChat) return;
        synchronized (recentChat) {
            recentChat.addLast(event.message.getUnformattedText());
            while (recentChat.size() > 8) recentChat.removeFirst();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!setupScreenOffered
            && (runtime.getStatus() == AuroraRuntimeManager.Status.NEEDS_INSTALL || knowledge.needsProfileInstall())
            && isMainMenu(minecraft.currentScreen)) {
            setupScreenOffered = true;
            AuroraBridgeMod.LOG.info("Opening Aurora first-run setup screen");
            minecraft.displayGuiScreen(new GuiAuroraSetup(minecraft.currentScreen, runtime, knowledge));
        }
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            if (inWorld) {
                worldSession++;
                replies.clear();
                overlay.clear();
                AI_IO.execute(ollama::clearHistory);
            }
            inWorld = false;
            readyNoticeShown = false;
            installNoticeShown = false;
            return;
        }

        inWorld = true;

        if (!readyNoticeShown && runtime.getStatus() == AuroraRuntimeManager.Status.READY) {
            readyNoticeShown = true;
            show("§d[Аврора] §7Я рядом. Нажми V, чтобы поговорить со мной.");
        }

        if (!installNoticeShown && runtime.getStatus() == AuroraRuntimeManager.Status.NEEDS_INSTALL) {
            installNoticeShown = true;
            show("§d[Аврора] §fМне нужен локальный мозг. Скоро здесь появится кнопка установки 💤");
        }

        String reply;
        while ((reply = replies.poll()) != null) {
            String cleaned = sanitizeForChat(reply);
            AuroraConversation.addAurora(cleaned);
            overlay.add(cleaned);
        }
    }

    @SubscribeEvent
    public void onKeyInput(cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (auroraChatKey.isPressed() && minecraft.thePlayer != null && minecraft.currentScreen == null) {
            minecraft.displayGuiScreen(new GuiAuroraChat("", GuiAuroraChat.Tab.AURORA));
        }
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!BridgeConfig.replaceVanillaChat) return;
        if (event.gui instanceof GuiChat && !(event.gui instanceof GuiAuroraChat)) {
            String initialText = "";
            try {
                initialText = ReflectionHelper
                    .getPrivateValue(GuiChat.class, (GuiChat) event.gui, "field_146409_v", "defaultInputFieldText");
            } catch (RuntimeException exception) {
                AuroraBridgeMod.LOG.debug("Could not read initial chat text", exception);
            }
            GuiAuroraChat.Tab tab = initialText.startsWith("/") ? GuiAuroraChat.Tab.GAME : GuiAuroraChat.getLastTab();
            event.gui = new GuiAuroraChat(initialText, tab);
        }
    }

    @SubscribeEvent
    public void onVanillaChatRender(RenderGameOverlayEvent.Chat event) {
        if (Minecraft.getMinecraft().currentScreen instanceof GuiAuroraChat) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onOverlayRender(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            overlay.render(Minecraft.getMinecraft(), event.resolution);
        }
    }

    static void sendPrompt(String prompt) {
        if (!INSTANCE.requestInFlight.compareAndSet(false, true)) {
            AuroraConversation.addAurora("Секунду, я ещё думаю над предыдущим сообщением.");
            return;
        }
        JsonObject context;
        List<String> chat;
        try {
            context = ContextSnapshot.capture();
            synchronized (INSTANCE.recentChat) {
                chat = new ArrayList<>(INSTANCE.recentChat);
            }
        } catch (RuntimeException exception) {
            INSTANCE.requestInFlight.set(false);
            AuroraBridgeMod.LOG.error("Could not capture Aurora context", exception);
            AuroraConversation.addAurora("Не смогла прочитать состояние мира. Попробуй ещё раз.");
            return;
        }
        AuroraConversation.addPlayer(prompt);
        long session = INSTANCE.worldSession;
        AI_IO.execute(() -> {
            try {
                if (!INSTANCE.runtime.ensureRunning()) {
                    if (INSTANCE.worldSession == session) {
                        INSTANCE.replies.add("Локальная Ollama пока не установлена. Открой экран установки Авроры.");
                    }
                    return;
                }
                String answer = INSTANCE.ollama.answer(prompt, context, chat);
                if (INSTANCE.worldSession == session) INSTANCE.replies.add(answer);
            } catch (Exception exception) {
                AuroraBridgeMod.LOG.error("Aurora could not answer", exception);
                if (INSTANCE.worldSession == session) {
                    INSTANCE.replies.add("Не смогла получить ответ от локальной модели: " + exception.getMessage());
                }
            } finally {
                INSTANCE.requestInFlight.set(false);
            }
        });
    }

    static boolean isThinking() {
        return INSTANCE.requestInFlight.get();
    }

    static void openSetup() {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.displayGuiScreen(new GuiAuroraSetup(minecraft.currentScreen, INSTANCE.runtime, INSTANCE.knowledge));
    }

    static void reloadKnowledge() {
        MAINTENANCE_IO.execute(() -> {
            INSTANCE.knowledge.reload();
            List<String> profiles = INSTANCE.knowledge.getActiveProfiles();
            INSTANCE.replies
                .add(profiles.isEmpty() ? "Внешние профили знаний не найдены." : "Загружены профили: " + profiles);
        });
    }

    static void updateKnowledge() {
        MAINTENANCE_IO.execute(() -> INSTANCE.updateKnowledgeFromCatalog(true));
    }

    static List<String> knowledgeProfiles() {
        return INSTANCE.knowledge.getActiveProfiles();
    }

    private void updateKnowledgeFromCatalog(boolean reportNoChange) {
        try {
            List<String> updated = knowledgeUpdater.updateInstalledProfiles(knowledge);
            if (!updated.isEmpty()) {
                knowledge.reload();
                replies.add("База знаний обновлена: " + updated + ".");
            } else if (reportNoChange) {
                replies.add("База знаний уже актуальна.");
            }
        } catch (Exception exception) {
            AuroraBridgeMod.LOG.warn("Could not update Aurora knowledge catalog; using local profiles", exception);
            if (reportNoChange) replies.add("Не удалось проверить обновления. Продолжаю работать с локальной базой.");
        }
    }

    private static String sanitizeForChat(String text) {
        String cleaned = text.replace('\r', ' ')
            .replace('\n', ' ')
            .replace('§', ' ')
            .replace("**", "")
            .replace("__", "")
            .replace("`", "");
        return cleaned.length() <= 1000 ? cleaned : cleaned.substring(0, 1000) + "…";
    }

    private static boolean isMainMenu(net.minecraft.client.gui.GuiScreen screen) {
        if (screen instanceof GuiMainMenu) return true;
        return screen != null && screen.getClass()
            .getName()
            .toLowerCase(Locale.ROOT)
            .contains("custommainmenu");
    }

    private static void show(String text) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer != null) minecraft.thePlayer.addChatMessage(new ChatComponentText(text));
    }
}
