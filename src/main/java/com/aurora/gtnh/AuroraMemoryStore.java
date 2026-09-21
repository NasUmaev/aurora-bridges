package com.aurora.gtnh;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Keeps a small, bounded event journal for the current world without blocking the render thread. */
final class AuroraMemoryStore {

    private static final int MAX_RECENT_EVENTS = 32;
    private static final long MAX_JOURNAL_BYTES = 1024L * 1024L;
    private static final JsonParser JSON = new JsonParser();

    private final Deque<String> recentEvents = new ArrayDeque<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Aurora-Memory-Writer");
        thread.setDaemon(true);
        return thread;
    });

    private File activeJournal;
    private String activeWorldName;

    void enterWorld(Minecraft minecraft) {
        if (activeJournal != null || minecraft.theWorld == null) return;

        WorldIdentity identity = identify(minecraft);
        File worldDirectory = new File(new File(minecraft.mcDataDir, "aurora/memory"), identity.id);
        activeJournal = new File(worldDirectory, "events.jsonl");
        activeWorldName = identity.name;
        loadRecent(activeJournal);
        record(new AuroraEvent("session_started", "Игрок вошёл в мир.", "info", false), minecraft);
        AuroraBridgeMod.LOG.info("Aurora memory opened for world '{}'", activeWorldName);
    }

    void leaveWorld() {
        File journal = activeJournal;
        if (journal == null) return;
        appendAsync(journal, eventLine(new AuroraEvent("session_ended", "Игрок вышел из мира.", "info", false), null));
        activeJournal = null;
        activeWorldName = null;
        synchronized (recentEvents) {
            recentEvents.clear();
        }
    }

    void record(AuroraEvent event, Minecraft minecraft) {
        File journal = activeJournal;
        if (event == null || journal == null) return;

        String memory = "[" + event.getType() + "] " + event.getSummary();
        synchronized (recentEvents) {
            recentEvents.addLast(memory);
            while (recentEvents.size() > MAX_RECENT_EVENTS) recentEvents.removeFirst();
        }
        appendAsync(journal, eventLine(event, minecraft));
    }

    void enrich(JsonObject context) {
        if (activeJournal == null) return;
        JsonObject memory = new JsonObject();
        memory.addProperty("world", activeWorldName);
        JsonArray recent = new JsonArray();
        synchronized (recentEvents) {
            int skip = Math.max(0, recentEvents.size() - 12);
            int index = 0;
            for (String event : recentEvents) {
                if (index++ >= skip) recent.add(new JsonPrimitive(event));
            }
        }
        memory.add("recentEvents", recent);
        context.add("memory", memory);
    }

    List<String> recent(int limit) {
        List<String> result = new ArrayList<>();
        synchronized (recentEvents) {
            int skip = Math.max(0, recentEvents.size() - Math.max(1, limit));
            int index = 0;
            for (String event : recentEvents) {
                if (index++ >= skip) result.add(event);
            }
        }
        return result;
    }

    private void loadRecent(File journal) {
        synchronized (recentEvents) {
            recentEvents.clear();
        }
        if (!journal.isFile()) return;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(journal), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject json = JSON.parse(line)
                    .getAsJsonObject();
                if (!json.has("type") || !json.has("summary")) continue;
                String memory = "[" + json.get("type")
                    .getAsString()
                    + "] "
                    + json.get("summary")
                        .getAsString();
                synchronized (recentEvents) {
                    recentEvents.addLast(memory);
                    while (recentEvents.size() > MAX_RECENT_EVENTS) recentEvents.removeFirst();
                }
            }
        } catch (Exception exception) {
            AuroraBridgeMod.LOG.warn("Could not read Aurora world memory", exception);
        }
    }

    private void appendAsync(File journal, String line) {
        writer.execute(() -> append(journal, line));
    }

    private static void append(File journal, String line) {
        try {
            File parent = journal.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Could not create " + parent);
            }
            rotateIfNeeded(journal);
            try (BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(journal, true), StandardCharsets.UTF_8))) {
                output.write(line);
                output.newLine();
            }
        } catch (Exception exception) {
            AuroraBridgeMod.LOG.warn("Could not write Aurora world memory", exception);
        }
    }

    private static void rotateIfNeeded(File journal) {
        if (!journal.isFile() || journal.length() < MAX_JOURNAL_BYTES) return;
        File previous = new File(journal.getParentFile(), "events.previous.jsonl");
        if (previous.exists() && !previous.delete()) {
            AuroraBridgeMod.LOG.warn("Could not replace old Aurora memory journal {}", previous);
            return;
        }
        if (!journal.renameTo(previous)) {
            AuroraBridgeMod.LOG.warn("Could not rotate Aurora memory journal {}", journal);
        }
    }

    private static String eventLine(AuroraEvent event, Minecraft minecraft) {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", System.currentTimeMillis());
        json.addProperty("type", event.getType());
        json.addProperty("importance", event.getImportance());
        json.addProperty("summary", event.getSummary());
        if (event.getData() != null) json.add("data", event.getData());
        if (minecraft != null && minecraft.thePlayer != null && minecraft.theWorld != null) {
            json.addProperty("worldTime", minecraft.theWorld.getWorldTime());
            json.addProperty("dimension", minecraft.thePlayer.dimension);
            json.addProperty("x", round(minecraft.thePlayer.posX));
            json.addProperty("y", round(minecraft.thePlayer.posY));
            json.addProperty("z", round(minecraft.thePlayer.posZ));
        }
        return json.toString();
    }

    private static WorldIdentity identify(Minecraft minecraft) {
        ServerData server = minecraft.func_147104_D();
        String name;
        String source;
        if (server != null) {
            name = server.serverName == null || server.serverName.isEmpty() ? "Сетевой мир" : server.serverName;
            source = "server|" + server.serverIP;
        } else {
            name = minecraft.theWorld.getWorldInfo()
                .getWorldName();
            source = "singleplayer|" + name + "|" + minecraft.theWorld.getSeed();
        }
        return new WorldIdentity(hash(source), name);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) result.append(String.format("%02x", digest[index] & 0xff));
            return result.toString();
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    private static final class WorldIdentity {

        private final String id;
        private final String name;

        private WorldIdentity(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
