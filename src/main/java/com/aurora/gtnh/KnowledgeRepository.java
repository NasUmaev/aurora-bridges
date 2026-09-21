package com.aurora.gtnh;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** Loads versioned knowledge exclusively from external profiles under minecraft/aurora/profiles. */
final class KnowledgeRepository {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_PROFILES = 16;
    private static final int MAX_ARTICLES = 2000;
    private static final long MAX_ARTICLE_BYTES = 128L * 1024L;
    private static final int MAX_RESULTS = 4;
    private static final JsonParser JSON = new JsonParser();
    private static final Set<String> STOP_WORDS = new HashSet<>(
        Arrays.asList(
            "а",
            "без",
            "бы",
            "в",
            "во",
            "где",
            "для",
            "его",
            "ее",
            "её",
            "из",
            "или",
            "как",
            "мне",
            "мой",
            "на",
            "не",
            "нужно",
            "о",
            "по",
            "почему",
            "с",
            "сделать",
            "что",
            "это",
            "этот",
            "я"));

    private volatile List<KnowledgeArticle> articles = Collections.emptyList();
    private volatile List<String> activeProfiles = Collections.emptyList();
    private volatile boolean loaded;
    private volatile boolean installedProfiles;

    void reload() {
        List<KnowledgeArticle> loadedArticles = new ArrayList<>();
        List<String> loadedProfiles = new ArrayList<>();
        File root = profilesDirectory();
        File[] profileDirectories = root.listFiles(File::isDirectory);
        if (profileDirectories == null) {
            articles = Collections.emptyList();
            activeProfiles = Collections.emptyList();
            installedProfiles = false;
            loaded = true;
            AuroraBridgeMod.LOG.info("No external Aurora knowledge profiles found in {}", root);
            return;
        }

        Arrays.sort(profileDirectories, Comparator.comparing(File::getName));
        boolean foundManifest = false;
        for (int index = 0; index < Math.min(profileDirectories.length, MAX_PROFILES); index++) {
            if (new File(profileDirectories[index], "manifest.json").isFile()) foundManifest = true;
            loadProfile(profileDirectories[index], loadedArticles, loadedProfiles);
            if (loadedArticles.size() >= MAX_ARTICLES) break;
        }
        articles = Collections.unmodifiableList(loadedArticles);
        activeProfiles = Collections.unmodifiableList(loadedProfiles);
        installedProfiles = foundManifest;
        loaded = true;
        AuroraBridgeMod.LOG
            .info("Loaded {} Aurora knowledge articles from profiles {}", loadedArticles.size(), loadedProfiles);
    }

    List<KnowledgeSearchResult> search(String query) {
        Set<String> tokens = tokens(query);
        if (tokens.isEmpty()) return Collections.emptyList();
        List<KnowledgeSearchResult> matches = new ArrayList<>();
        for (KnowledgeArticle article : articles) {
            int score = score(article, tokens, query);
            if (score > 0) matches.add(new KnowledgeSearchResult(article, score));
        }
        matches.sort((left, right) -> Integer.compare(right.getScore(), left.getScore()));
        if (matches.size() > MAX_RESULTS) return new ArrayList<>(matches.subList(0, MAX_RESULTS));
        return matches;
    }

    boolean hasProfiles() {
        return !activeProfiles.isEmpty();
    }

    boolean needsProfileInstall() {
        return loaded && !installedProfiles;
    }

    List<String> getActiveProfiles() {
        return activeProfiles;
    }

    static File profilesDirectory() {
        return new File(AuroraRuntimeManager.auroraDirectory(), "profiles");
    }

    private static void loadProfile(File directory, List<KnowledgeArticle> result, List<String> profiles) {
        File manifestFile = new File(directory, "manifest.json");
        if (!manifestFile.isFile() || manifestFile.length() > MAX_ARTICLE_BYTES) return;
        try {
            JsonObject manifest = readJson(manifestFile);
            if (integer(manifest, "schemaVersion", 0) != SCHEMA_VERSION) return;
            if (!string(manifest, "minecraftVersion", "").equals("1.7.10")) return;
            if (!booleanValue(manifest, "enabled", true) || !modsAvailable(manifest) || !environmentMatches(manifest))
                return;

            String id = string(manifest, "id", directory.getName());
            String displayName = string(manifest, "displayName", id);
            File knowledge = new File(directory, "knowledge");
            File[] files = knowledge.listFiles(
                file -> file.isFile() && file.getName()
                    .endsWith(".json"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparing(File::getName));
            int before = result.size();
            for (File file : files) {
                if (result.size() >= MAX_ARTICLES || file.length() > MAX_ARTICLE_BYTES) break;
                KnowledgeArticle article = readArticle(id, file);
                if (article != null) result.add(article);
            }
            if (result.size() > before) profiles.add(displayName);
        } catch (Exception exception) {
            AuroraBridgeMod.LOG.warn("Could not load Aurora knowledge profile {}", directory, exception);
        }
    }

    private static KnowledgeArticle readArticle(String profile, File file) throws Exception {
        JsonObject json = readJson(file);
        String id = string(json, "id", "");
        String title = string(json, "title", "");
        String body = string(json, "body", "");
        if (id.isEmpty() || title.isEmpty() || body.isEmpty()) return null;
        return new KnowledgeArticle(
            profile,
            id,
            title,
            strings(json.getAsJsonArray("aliases")),
            strings(json.getAsJsonArray("tags")),
            body,
            string(json, "sourceLabel", profile),
            safeUrl(string(json, "sourceUrl", "")));
    }

    private static int score(KnowledgeArticle article, Set<String> queryTokens, String rawQuery) {
        String normalizedTitle = normalize(article.getTitle());
        String normalizedQuery = normalize(rawQuery);
        int score = contains(normalizedTitle, normalizedQuery) || contains(normalizedQuery, normalizedTitle) ? 20 : 0;
        Set<String> title = tokens(article.getTitle());
        Set<String> aliases = tokens(String.join(" ", article.getAliases()));
        Set<String> tags = tokens(String.join(" ", article.getTags()));
        Set<String> body = tokens(article.getBody());
        for (String token : queryTokens) {
            if (matches(title, token)) score += 9;
            if (matches(aliases, token)) score += 7;
            if (matches(tags, token)) score += 5;
            if (matches(body, token)) score += 1;
        }
        return score;
    }

    private static boolean matches(Set<String> candidates, String query) {
        if (candidates.contains(query)) return true;
        if (query.length() < 4) return false;
        for (String candidate : candidates) {
            int shared = Math.min(Math.min(candidate.length(), query.length()), 6);
            if (shared >= 4 && candidate.regionMatches(0, query, 0, shared)) return true;
        }
        return false;
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        for (String token : normalize(text).split("[^\\p{L}\\p{Nd}_:.-]+")) {
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) result.add(token);
        }
        return result;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFC)
            .trim();
    }

    private static boolean contains(String value, String query) {
        return !query.isEmpty() && value.contains(query);
    }

    private static JsonObject readJson(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line)
                .append('\n');
        }
        return JSON.parse(content.toString())
            .getAsJsonObject();
    }

    private static boolean modsAvailable(JsonObject manifest) {
        JsonArray required = manifest.getAsJsonArray("requiredMods");
        if (required == null) return true;
        for (JsonElement mod : required) {
            if (!Loader.isModLoaded(mod.getAsString())) return false;
        }
        return true;
    }

    private static boolean environmentMatches(JsonObject manifest) {
        if (!booleanValue(manifest, "vanillaOnly", false)) return true;
        Set<String> allowed = new HashSet<>(Arrays.asList("mcp", "FML", "Forge"));
        allowed.addAll(strings(manifest.getAsJsonArray("allowedMods")));
        for (ModContainer mod : Loader.instance()
            .getActiveModList()) {
            if (!allowed.contains(mod.getModId())) return false;
        }
        return true;
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (JsonElement element : array) result.add(element.getAsString());
        return result;
    }

    private static String string(JsonObject object, String name, String fallback) {
        return object.has(name) ? object.get(name)
            .getAsString() : fallback;
    }

    private static int integer(JsonObject object, String name, int fallback) {
        return object.has(name) ? object.get(name)
            .getAsInt() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String name, boolean fallback) {
        return object.has(name) ? object.get(name)
            .getAsBoolean() : fallback;
    }

    private static String safeUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://") ? value : "";
    }
}
