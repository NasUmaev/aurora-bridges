package com.aurora.gtnh.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Generates the review checklist from the real Minecraft 1.7.10 block registry source. */
public final class VanillaBlockInventoryGenerator {

    private static final Pattern REGISTRATION = Pattern
        .compile("blockRegistry\\.addObject\\((\\d+),\\s*\"([a-z0-9_]+)\"");
    private static final JsonParser JSON = new JsonParser();
    private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private VanillaBlockInventoryGenerator() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Expected Block.java, profile directory, and output file");
        }
        File blockSource = new File(arguments[0]).getCanonicalFile();
        File profileDirectory = new File(arguments[1]).getCanonicalFile();
        File outputFile = new File(arguments[2]).getCanonicalFile();
        require(blockSource.isFile(), "Missing Minecraft Block.java: " + blockSource);
        require(profileDirectory.isDirectory(), "Missing knowledge profile: " + profileDirectory);

        Map<Integer, String> registrations = readRegistrations(blockSource);
        require(registrations.size() == 171, "Expected 171 vanilla 1.7.10 blocks, found " + registrations.size());
        Map<String, String> curatedArticles = readCuratedBlockArticles(profileDirectory);

        JsonObject inventory = new JsonObject();
        inventory.addProperty("schemaVersion", 1);
        inventory.addProperty("gameVersion", "1.7.10");
        inventory.addProperty("sourceLabel", "Minecraft Java Edition 1.7.10 — реестр блоков");
        inventory.addProperty("sourceReference", "net.minecraft.block.Block.registerBlocks");
        inventory.addProperty("sourceSha256", sha256(blockSource));
        inventory.addProperty("totalBlocks", registrations.size());
        inventory.addProperty("curatedBlocks", curatedArticles.size());

        List<BlockEntry> blocks = new ArrayList<>();
        for (Map.Entry<Integer, String> registration : registrations.entrySet()) {
            String registryName = "minecraft:" + registration.getValue();
            String articleId = curatedArticles.get(registryName);
            blocks.add(
                new BlockEntry(
                    registration.getKey(),
                    registryName,
                    articleId == null ? "pending" : "curated",
                    articleId));
        }
        inventory.add("blocks", PRETTY_JSON.toJsonTree(blocks));

        Files.createDirectories(
            outputFile.toPath()
                .getParent());
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
            PRETTY_JSON.toJson(inventory, writer);
            writer.write('\n');
        }
        System.out.println(
            "Generated block inventory: " + registrations.size() + " total, " + curatedArticles.size() + " curated");
    }

    private static Map<Integer, String> readRegistrations(File source) throws Exception {
        Map<Integer, String> registrations = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = REGISTRATION.matcher(line);
                if (!matcher.find()) continue;
                int numericId = Integer.parseInt(matcher.group(1));
                require(
                    registrations.put(numericId, matcher.group(2)) == null,
                    "Duplicate numeric block id " + numericId);
            }
        }
        return registrations;
    }

    private static Map<String, String> readCuratedBlockArticles(File profileDirectory) throws Exception {
        Map<String, String> result = new HashMap<>();
        File blocksDirectory = new File(new File(profileDirectory, "knowledge"), "blocks");
        File[] files = blocksDirectory.listFiles((directory, name) -> name.endsWith(".json"));
        if (files == null) return result;
        for (File file : files) {
            JsonObject article = readObject(file);
            if (integer(article, "schemaVersion", 1) != 2 || !"block".equals(string(article, "kind"))) continue;
            JsonObject subject = article.getAsJsonObject("subject");
            String registryName = string(subject, "registryName");
            String articleId = string(article, "id");
            require(!registryName.isEmpty() && !articleId.isEmpty(), "Incomplete block article: " + file);
            require(result.put(registryName, articleId) == null, "Duplicate block article for " + registryName);
        }
        return result;
    }

    private static JsonObject readObject(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line)
                .append('\n');
        }
        JsonElement parsed = JSON.parse(content.toString());
        require(parsed.isJsonObject(), "Expected JSON object: " + file);
        return parsed.getAsJsonObject();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name)
            || !object.get(name)
                .isJsonPrimitive())
            return "";
        return object.get(name)
            .getAsString();
    }

    private static int integer(JsonObject object, String name, int fallback) {
        if (!object.has(name) || !object.get(name)
            .isJsonPrimitive()) return fallback;
        return object.get(name)
            .getAsInt();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static final class BlockEntry {

        private final int numericId;
        private final String registryName;
        private final String status;
        private final String articleId;

        private BlockEntry(int numericId, String registryName, String status, String articleId) {
            this.numericId = numericId;
            this.registryName = registryName;
            this.status = status;
            this.articleId = articleId;
        }
    }
}
