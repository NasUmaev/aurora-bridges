package com.aurora.gtnh;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Checks the small online catalog and atomically updates profiles that are already installed. */
final class KnowledgeCatalogUpdater {

    private static final String CATALOG_URL = "https://raw.githubusercontent.com/NasUmaev/aurora-bridges/main/knowledge-catalog/catalog.json";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_CATALOG_BYTES = 512 * 1024;
    private static final JsonParser JSON = new JsonParser();

    List<String> updateInstalledProfiles(KnowledgeRepository repository) throws Exception {
        String content = downloadCatalog();
        JsonObject catalog = JSON.parse(content)
            .getAsJsonObject();
        if (!catalog.has("schemaVersion") || catalog.get("schemaVersion")
            .getAsInt() != SCHEMA_VERSION) throw new IllegalStateException("Unsupported knowledge catalog schema");
        cacheCatalog(content);

        List<String> updated = new ArrayList<>();
        JsonArray profiles = catalog.getAsJsonArray("profiles");
        if (profiles == null) return updated;
        for (JsonElement element : profiles) {
            if (!element.isJsonObject()) continue;
            KnowledgeProfileDescriptor descriptor;
            try {
                descriptor = KnowledgeProfileDescriptor.fromJson(element.getAsJsonObject());
            } catch (IllegalArgumentException exception) {
                AuroraBridgeMod.LOG.warn("Ignoring invalid Aurora knowledge catalog entry", exception);
                continue;
            }
            String installedVersion = repository.getInstalledProfileVersion(descriptor.getId());
            if (installedVersion == null || compareVersions(descriptor.getVersion(), installedVersion) <= 0) continue;
            new KnowledgeProfileInstaller(descriptor).install((message, progress) -> AuroraBridgeMod.LOG.info(message));
            updated.add(descriptor.getId() + " " + descriptor.getVersion());
        }
        return updated;
    }

    static int compareVersions(String left, String right) {
        int[] leftParts = versionParts(left);
        int[] rightParts = versionParts(right);
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int leftPart = index < leftParts.length ? leftParts[index] : 0;
            int rightPart = index < rightParts.length ? rightParts[index] : 0;
            if (leftPart != rightPart) return Integer.compare(leftPart, rightPart);
        }
        return 0;
    }

    private static int[] versionParts(String version) {
        if (!KnowledgeProfileDescriptor.isValidVersion(version)) return new int[] { 0 };
        String[] parts = version.split("\\.");
        int[] result = new int[parts.length];
        for (int index = 0; index < parts.length; index++) result[index] = Integer.parseInt(parts[index]);
        return result;
    }

    private static String downloadCatalog() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(CATALOG_URL).openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "AuroraGTNH/0.1");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IllegalStateException("Knowledge catalog HTTP " + code);
            long declared = connection.getContentLengthLong();
            if (declared > MAX_CATALOG_BYTES) throw new IllegalStateException("Knowledge catalog is too large");
            StringBuilder result = new StringBuilder();
            int total = 0;
            char[] buffer = new char[4096];
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                int count;
                while ((count = reader.read(buffer)) >= 0) {
                    total += count;
                    if (total > MAX_CATALOG_BYTES) throw new IllegalStateException("Knowledge catalog is too large");
                    result.append(buffer, 0, count);
                }
            }
            return result.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static void cacheCatalog(String content) throws Exception {
        File directory = new File(AuroraRuntimeManager.auroraDirectory(), "catalog");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Could not create knowledge catalog cache");
        File pending = new File(directory, "catalog.json.part");
        File destination = new File(directory, "catalog.json");
        try (BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(pending), StandardCharsets.UTF_8))) {
            writer.write(content);
        }
        SafeFileOps.replaceFile(pending, destination);
    }
}
