package com.aurora.gtnh;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class BridgeConfig {

    public static boolean enabled = true;
    public static String ollamaUrl = "http://127.0.0.1:11434";
    public static String ollamaModel = "qwen3:8b";
    public static boolean captureIncomingChat = true;
    public static boolean replaceVanillaChat = true;
    public static boolean proactiveComments = true;
    public static int proactiveCooldownSeconds = 45;
    public static double criticalHealth = 6.0D;

    private BridgeConfig() {}

    public static void load(File file) {
        Configuration config = new Configuration(file);
        enabled = config.getBoolean("enabled", "aurora", enabled, "Enable the local Aurora companion.");
        ollamaUrl = config.getString(
            "url",
            "ollama",
            ollamaUrl,
            "Local Ollama base URL. For safety, keep this on loopback (127.0.0.1).");
        ollamaModel = config.getString("model", "ollama", ollamaModel, "Local model used by Aurora.");
        captureIncomingChat = config.getBoolean(
            "captureIncomingChat",
            "privacy",
            captureIncomingChat,
            "Send received game chat to the local bridge.");
        replaceVanillaChat = config.getBoolean(
            "replaceVanillaChat",
            "chat",
            replaceVanillaChat,
            "Open the expanded Aurora Chat instead of Minecraft's vanilla chat screen.");
        proactiveComments = config.getBoolean(
            "enabled",
            "proactive",
            proactiveComments,
            "Let Aurora comment on important player events without being asked.");
        proactiveCooldownSeconds = config.getInt(
            "cooldownSeconds",
            "proactive",
            proactiveCooldownSeconds,
            10,
            600,
            "Minimum delay between proactive AI comments.");
        criticalHealth = config.getFloat(
            "criticalHealth",
            "proactive",
            (float) criticalHealth,
            1.0F,
            20.0F,
            "Health value that triggers a critical-health observation.");
        if (config.hasChanged()) config.save();
    }
}
