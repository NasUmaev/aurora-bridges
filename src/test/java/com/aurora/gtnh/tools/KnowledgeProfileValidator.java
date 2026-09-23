package com.aurora.gtnh.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Build-time validation for external Aurora knowledge profiles. */
public final class KnowledgeProfileValidator {

    private static final long MAX_ARTICLE_BYTES = 128L * 1024L;
    private static final JsonParser JSON = new JsonParser();
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

    private KnowledgeProfileValidator() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected one knowledge profile directory");
        File profileDirectory = new File(arguments[0]).getCanonicalFile();
        File manifestFile = new File(profileDirectory, "manifest.json");
        require(manifestFile.isFile(), "Missing profile manifest: " + manifestFile);

        JsonObject manifest = readObject(manifestFile);
        require(integer(manifest, "schemaVersion") == 1, "Unsupported manifest schemaVersion");
        require("1.7.10".equals(string(manifest, "minecraftVersion")), "Profile must target Minecraft 1.7.10");
        require(!string(manifest, "id").isEmpty(), "Manifest id is required");

        File knowledgeDirectory = new File(profileDirectory, "knowledge");
        List<File> articles = new ArrayList<>();
        collectArticles(knowledgeDirectory, knowledgeDirectory, articles, 0);
        require(
            integer(manifest, "articleCount") == articles.size(),
            "manifest articleCount is " + integer(manifest, "articleCount") + ", but found " + articles.size());

        Set<String> articleIds = new HashSet<>();
        for (File article : articles) {
            JsonObject json = readObject(article);
            int schemaVersion = json.has("schemaVersion") ? integer(json, "schemaVersion") : 1;
            String articleId = string(json, "id");
            require(!articleId.isEmpty(), article + ": id is required");
            require(articleIds.add(articleId), article + ": duplicate article id " + articleId);
            if (schemaVersion == 1) {
                validateLegacyArticle(article, json);
            } else if (schemaVersion == 2) {
                validateStructuredArticle(article, json);
            } else {
                throw new IllegalArgumentException(article + ": unsupported schemaVersion " + schemaVersion);
            }
        }
        System.out.println("Validated " + articles.size() + " knowledge articles in " + profileDirectory);
    }

    private static void validateLegacyArticle(File file, JsonObject json) {
        require(!string(json, "title").isEmpty(), file + ": title is required");
        require(!string(json, "body").isEmpty(), file + ": body is required");
        validateStringArray(file, json, "aliases", false);
        validateStringArray(file, json, "tags", false);
    }

    private static void validateStructuredArticle(File file, JsonObject json) {
        require(string(json, "id").matches("[a-z0-9._@-]{1,128}"), file + ": invalid id");
        require(ARTICLE_KINDS.contains(string(json, "kind")), file + ": invalid kind");
        require("1.7.10".equals(string(json, "gameVersion")), file + ": invalid gameVersion");
        require(!string(json, "title").isEmpty(), file + ": title is required");
        require(!string(json, "summary").isEmpty(), file + ": summary is required");
        validateStringArray(file, json, "aliases", true);
        validateStringArray(file, json, "tags", true);

        JsonObject subject = object(json, "subject", file);
        require(!string(subject, "id").isEmpty(), file + ": subject.id is required");

        JsonArray sources = array(json, "sources", file);
        require(sources.size() > 0, file + ": at least one source is required");
        Set<String> sourceIds = new HashSet<>();
        for (JsonElement element : sources) {
            require(element.isJsonObject(), file + ": every source must be an object");
            JsonObject source = element.getAsJsonObject();
            String sourceId = string(source, "id");
            require(sourceId.matches("[a-z0-9._-]{1,80}"), file + ": invalid source id");
            require(sourceIds.add(sourceId), file + ": duplicate source id " + sourceId);
            require(!string(source, "label").isEmpty(), file + ": source label is required");
            require(!string(source, "type").isEmpty(), file + ": source type is required");
        }

        JsonArray facts = array(json, "facts", file);
        require(facts.size() > 0, file + ": at least one fact is required");
        Set<String> factKeys = new HashSet<>();
        for (JsonElement element : facts) {
            require(element.isJsonObject(), file + ": every fact must be an object");
            JsonObject fact = element.getAsJsonObject();
            String key = string(fact, "key");
            require(key.matches("[a-z0-9._-]{1,80}"), file + ": invalid fact key");
            require(factKeys.add(key), file + ": duplicate fact key " + key);
            require(!string(fact, "text").isEmpty(), file + ": fact text is required");
            Set<String> references = validateStringArray(file, fact, "sourceIds", true);
            require(!references.isEmpty(), file + ": every fact needs a source");
            require(sourceIds.containsAll(references), file + ": fact refers to an unknown source");
            if (fact.has("conditions")) validateStringArray(file, fact, "conditions", true);
        }

        JsonArray relations = array(json, "relations", file);
        for (JsonElement element : relations) {
            require(element.isJsonObject(), file + ": every relation must be an object");
            JsonObject relation = element.getAsJsonObject();
            require(!string(relation, "type").isEmpty(), file + ": relation type is required");
            require(!string(relation, "target").isEmpty(), file + ": relation target is required");
        }
    }

    private static Set<String> validateStringArray(File file, JsonObject json, String name, boolean required) {
        if (!json.has(name)) {
            require(!required, file + ": " + name + " is required");
            return new HashSet<>();
        }
        JsonArray values = array(json, name, file);
        Set<String> unique = new HashSet<>();
        for (JsonElement value : values) {
            require(
                value.isJsonPrimitive() && value.getAsJsonPrimitive()
                    .isString(),
                file + ": " + name + " must contain only strings");
            String text = value.getAsString();
            require(!text.isEmpty(), file + ": " + name + " cannot contain empty strings");
            require(unique.add(text), file + ": " + name + " contains duplicate value " + text);
        }
        return unique;
    }

    private static void collectArticles(File root, File directory, List<File> result, int depth) throws Exception {
        require(depth <= 8, "Knowledge directory nesting is too deep: " + directory);
        require(directory.isDirectory(), "Missing knowledge directory: " + directory);
        require(!Files.isSymbolicLink(directory.toPath()), "Symbolic links are not allowed: " + directory);
        String rootPath = root.getCanonicalPath() + File.separator;
        String directoryPath = directory.getCanonicalPath() + File.separator;
        require(directoryPath.startsWith(rootPath), "Knowledge path escapes its profile: " + directory);
        File[] children = directory.listFiles();
        require(children != null, "Cannot list knowledge directory: " + directory);
        Arrays.sort(
            children,
            (left, right) -> left.getName()
                .compareTo(right.getName()));
        for (File child : children) {
            require(!Files.isSymbolicLink(child.toPath()), "Symbolic links are not allowed: " + child);
            if (child.isDirectory()) {
                collectArticles(root, child, result, depth + 1);
            } else if (child.getName()
                .endsWith(".json")) {
                    require(
                        child.length() > 0L && child.length() <= MAX_ARTICLE_BYTES,
                        "Invalid article size: " + child);
                    result.add(child);
                }
        }
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
        require(parsed.isJsonObject(), file + ": root must be a JSON object");
        return parsed.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String name, File file) {
        require(
            object.has(name) && object.get(name)
                .isJsonArray(),
            file + ": " + name + " must be an array");
        return object.getAsJsonArray(name);
    }

    private static JsonObject object(JsonObject parent, String name, File file) {
        require(
            parent.has(name) && parent.get(name)
                .isJsonObject(),
            file + ": " + name + " must be an object");
        return parent.getAsJsonObject(name);
    }

    private static String string(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name)
            .isJsonPrimitive()) return "";
        return object.get(name)
            .getAsString();
    }

    private static int integer(JsonObject object, String name) {
        require(
            object.has(name) && object.get(name)
                .isJsonPrimitive(),
            name + " must be an integer");
        return object.get(name)
            .getAsInt();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
