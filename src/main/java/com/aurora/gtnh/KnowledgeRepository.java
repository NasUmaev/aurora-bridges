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
    private static final int MAX_ARTICLES = 50000;
    private static final long MAX_ARTICLE_BYTES = 128L * 1024L;
    private static final long MAX_PROFILE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_RESULTS = 4;
    private static final int MIN_RELEVANCE_SCORE = 5;
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
    private static final Set<String> ARTICLE_KINDS = new HashSet<>(
        Arrays.asList(
            "block",
            "item",
            "mob",
            "recipe",
            "drop",
            "enchantment",
            "effect",
            "biome",
            "structure",
            "mechanic",
            "guide"));

    private volatile List<IndexedArticle> articles = Collections.emptyList();
    private volatile List<String> activeProfiles = Collections.emptyList();
    private volatile boolean loaded;
    private volatile boolean installedProfiles;

    void reload() {
        List<IndexedArticle> loadedArticles = new ArrayList<>();
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
        for (int index = 0; index < Math.min(profileDirectories.length, MAX_PROFILES); index++) {
            loadProfile(profileDirectories[index], loadedArticles, loadedProfiles);
            if (loadedArticles.size() >= MAX_ARTICLES) break;
        }
        articles = Collections.unmodifiableList(loadedArticles);
        activeProfiles = Collections.unmodifiableList(loadedProfiles);
        installedProfiles = !loadedProfiles.isEmpty();
        loaded = true;
        AuroraBridgeMod.LOG
            .info("Loaded {} Aurora knowledge articles from profiles {}", loadedArticles.size(), loadedProfiles);
    }

    List<KnowledgeSearchResult> search(String query) {
        Set<String> tokens = tokens(query);
        if (tokens.isEmpty()) return Collections.emptyList();
        List<KnowledgeSearchResult> matches = new ArrayList<>();
        for (IndexedArticle indexed : articles) {
            int score = score(indexed, tokens, query);
            if (score >= MIN_RELEVANCE_SCORE) matches.add(new KnowledgeSearchResult(indexed.article, score));
        }
        matches.sort((left, right) -> Integer.compare(right.getScore(), left.getScore()));
        if (matches.size() > MAX_RESULTS) return new ArrayList<>(matches.subList(0, MAX_RESULTS));
        return matches;
    }

    boolean needsProfileInstall() {
        return loaded && !installedProfiles;
    }

    List<String> getActiveProfiles() {
        return activeProfiles;
    }

    String getInstalledProfileVersion(String profileId) {
        if (profileId == null || !profileId.matches("[a-z0-9._-]{1,64}")) return null;
        File manifestFile = new File(new File(profilesDirectory(), profileId), "manifest.json");
        if (!manifestFile.isFile() || manifestFile.length() > MAX_ARTICLE_BYTES) return null;
        try {
            JsonObject manifest = readJson(manifestFile);
            if (!profileId.equals(string(manifest, "id", ""))) return null;
            String version = string(manifest, "profileVersion", "0");
            return KnowledgeProfileDescriptor.isValidVersion(version) ? version : null;
        } catch (Exception exception) {
            AuroraBridgeMod.LOG.warn("Could not read installed Aurora profile version for {}", profileId, exception);
            return null;
        }
    }

    static File profilesDirectory() {
        return new File(AuroraRuntimeManager.auroraDirectory(), "profiles");
    }

    private static void loadProfile(File directory, List<IndexedArticle> result, List<String> profiles) {
        File manifestFile = new File(directory, "manifest.json");
        if (!manifestFile.isFile() || manifestFile.length() > MAX_ARTICLE_BYTES) return;
        try {
            JsonObject manifest = readJson(manifestFile);
            if (integer(manifest, "schemaVersion", 0) != SCHEMA_VERSION) return;
            if (!string(manifest, "minecraftVersion", "").equals("1.7.10")) return;
            if (!booleanValue(manifest, "enabled", true) || !modsAvailable(manifest) || !environmentMatches(manifest))
                return;

            String id = string(manifest, "id", directory.getName());
            if (!id.matches("[a-z0-9._-]{1,64}") || !id.equals(directory.getName())) return;
            String displayName = string(manifest, "displayName", id);
            File knowledge = new File(directory, "knowledge");
            List<File> files = new ArrayList<>();
            collectArticleFiles(knowledge, knowledge, files, 0);
            files.sort(Comparator.comparing(File::getPath));
            int before = result.size();
            long loadedBytes = 0L;
            Set<String> articleIds = new HashSet<>();
            for (File file : files) {
                if (result.size() >= MAX_ARTICLES) break;
                long length = file.length();
                if (length <= 0L || length > MAX_ARTICLE_BYTES) {
                    AuroraBridgeMod.LOG.warn("Ignoring invalid-sized Aurora knowledge article {}", file);
                    continue;
                }
                loadedBytes += length;
                if (loadedBytes > MAX_PROFILE_BYTES) {
                    AuroraBridgeMod.LOG.warn("Aurora knowledge profile {} exceeds the size limit", id);
                    break;
                }
                try {
                    KnowledgeArticle article = readArticle(id, file);
                    if (article == null || !articleIds.add(article.getId())) {
                        AuroraBridgeMod.LOG.warn("Ignoring empty or duplicate Aurora knowledge article {}", file);
                        continue;
                    }
                    result.add(new IndexedArticle(article));
                } catch (Exception exception) {
                    AuroraBridgeMod.LOG.warn("Ignoring malformed Aurora knowledge article {}", file, exception);
                }
            }
            if (result.size() > before) profiles.add(displayName);
        } catch (Exception exception) {
            AuroraBridgeMod.LOG.warn("Could not load Aurora knowledge profile {}", directory, exception);
        }
    }

    private static void collectArticleFiles(File root, File directory, List<File> result, int depth) throws Exception {
        if (depth > 4 || result.size() >= MAX_ARTICLES || java.nio.file.Files.isSymbolicLink(directory.toPath()))
            return;
        String canonicalRoot = root.getCanonicalPath() + File.separator;
        String canonicalDirectory = directory.getCanonicalPath() + File.separator;
        if (!canonicalDirectory.startsWith(canonicalRoot)) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (result.size() >= MAX_ARTICLES) break;
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) {
                collectArticleFiles(root, child, result, depth + 1);
            } else if (child.isFile() && child.getName()
                .endsWith(".json")) {
                    result.add(child);
                }
        }
    }

    private static KnowledgeArticle readArticle(String profile, File file) throws Exception {
        JsonObject json = readJson(file);
        int schemaVersion = integer(json, "schemaVersion", 1);
        if (schemaVersion == 2) return readStructuredArticle(profile, json);
        if (schemaVersion != 1) return null;
        return readLegacyArticle(profile, json);
    }

    private static KnowledgeArticle readLegacyArticle(String profile, JsonObject json) {
        String id = string(json, "id", "");
        String title = string(json, "title", "");
        String body = string(json, "body", "");
        if (id.isEmpty() || title.isEmpty() || body.isEmpty()) return null;
        return new KnowledgeArticle(
            id,
            title,
            strings(json.getAsJsonArray("aliases")),
            strings(json.getAsJsonArray("tags")),
            body,
            string(json, "sourceLabel", profile));
    }

    private static KnowledgeArticle readStructuredArticle(String profile, JsonObject json) {
        String id = string(json, "id", "");
        String kind = string(json, "kind", "");
        String title = string(json, "title", "");
        String summary = string(json, "summary", "");
        if (!id.matches("[a-z0-9._@-]{1,128}") || !ARTICLE_KINDS.contains(kind)
            || title.isEmpty()
            || summary.isEmpty()
            || !"1.7.10".equals(string(json, "gameVersion", ""))) return null;

        JsonObject subject = json.has("subject") && json.get("subject")
            .isJsonObject() ? json.getAsJsonObject("subject") : null;
        if (subject == null || string(subject, "id", "").isEmpty()) return null;

        List<String> aliases = strings(json.getAsJsonArray("aliases"));
        addSearchAlias(aliases, subject, "id");
        addSearchAlias(aliases, subject, "registryName");
        List<String> tags = strings(json.getAsJsonArray("tags"));
        tags.add(kind);

        JsonArray sources = json.getAsJsonArray("sources");
        if (sources == null || sources.size() == 0) return null;
        Set<String> sourceIds = new HashSet<>();
        String sourceLabel = profile;
        for (JsonElement element : sources) {
            if (!element.isJsonObject()) return null;
            JsonObject source = element.getAsJsonObject();
            String sourceId = string(source, "id", "");
            String label = string(source, "label", "");
            if (sourceId.isEmpty() || label.isEmpty() || !sourceIds.add(sourceId)) return null;
            if (sourceLabel.equals(profile)) sourceLabel = label;
        }

        JsonArray facts = json.getAsJsonArray("facts");
        if (facts == null || facts.size() == 0) return null;
        StringBuilder body = new StringBuilder(summary);
        Set<String> factKeys = new HashSet<>();
        for (JsonElement element : facts) {
            if (!element.isJsonObject()) return null;
            JsonObject fact = element.getAsJsonObject();
            String key = string(fact, "key", "");
            String text = string(fact, "text", "");
            List<String> references = strings(fact.getAsJsonArray("sourceIds"));
            if (key.isEmpty() || text.isEmpty()
                || !factKeys.add(key)
                || references.isEmpty()
                || !sourceIds.containsAll(references)) return null;
            body.append("\n- ")
                .append(text);
            List<String> conditions = strings(fact.getAsJsonArray("conditions"));
            if (!conditions.isEmpty()) body.append(" Условия: ")
                .append(String.join("; ", conditions))
                .append('.');
        }
        return new KnowledgeArticle(id, title, aliases, tags, body.toString(), sourceLabel);
    }

    private static void addSearchAlias(List<String> aliases, JsonObject subject, String name) {
        if (!subject.has(name) || !subject.get(name)
            .isJsonPrimitive()) return;
        String value = subject.get(name)
            .getAsString();
        if (!value.isEmpty() && !aliases.contains(value)) aliases.add(value);
    }

    private static int score(IndexedArticle article, Set<String> queryTokens, String rawQuery) {
        String normalizedQuery = normalize(rawQuery);
        int score = contains(article.normalizedTitle, normalizedQuery)
            || contains(normalizedQuery, article.normalizedTitle) ? 20 : 0;
        for (String token : queryTokens) {
            if (matches(article.titleTokens, token)) score += 9;
            if (matches(article.aliasTokens, token)) score += 7;
            if (matches(article.tagTokens, token)) score += 5;
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
        return Normalizer.normalize(
            value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е'),
            Normalizer.Form.NFC)
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

    private static final class IndexedArticle {

        private final KnowledgeArticle article;
        private final String normalizedTitle;
        private final Set<String> titleTokens;
        private final Set<String> aliasTokens;
        private final Set<String> tagTokens;

        private IndexedArticle(KnowledgeArticle article) {
            this.article = article;
            normalizedTitle = normalize(article.getTitle());
            titleTokens = tokens(article.getTitle());
            aliasTokens = tokens(String.join(" ", article.getAliases()));
            tagTokens = tokens(String.join(" ", article.getTags()));
        }
    }
}
