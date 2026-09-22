package com.aurora.gtnh;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Talks directly to the loopback Ollama API; no external Python bridge is required. */
public final class OllamaClient {

    private static final JsonParser JSON = new JsonParser();
    private final Deque<JsonObject> history = new ArrayDeque<>();
    private final KnowledgeRepository knowledge;

    OllamaClient(KnowledgeRepository knowledge) {
        this.knowledge = knowledge;
    }

    public synchronized String answer(String prompt, JsonObject context, List<String> recentChat, String activeRecipes)
        throws Exception {
        return complete("user", prompt, context, recentChat, activeRecipes, 180);
    }

    public synchronized String reactToEvent(String event, JsonObject context, List<String> recentChat)
        throws Exception {
        String instruction = "Событие игры: " + event
            + " Отреагируй сама одной короткой уместной репликой. Не задавай вопрос и не пересказывай технические поля.";
        return complete("system", instruction, context, recentChat, "", 80);
    }

    private String complete(String role, String prompt, JsonObject context, List<String> recentChat,
        String activeRecipes, int maxTokens) throws Exception {
        List<KnowledgeSearchResult> sources = knowledge.search(knowledgeQuery(prompt, context));
        if ("user".equals(role) && sources.isEmpty() && activeRecipes.isEmpty() && looksLikeKnowledgeQuestion(prompt)) {
            String answer = "Я пока не знаю это наверняка: в подключённой базе нет подходящей статьи. "
                + "Не хочу придумывать и вводить тебя в заблуждение 😅";
            remember(role, prompt, answer);
            return answer;
        }
        JsonArray messages = new JsonArray();
        messages.add(
            message(
                "system",
                PromptComposer.compose(context, recentChat, knowledge.getActiveProfiles(), sources, activeRecipes)));
        for (JsonObject previous : history) messages.add(previous);
        messages.add(message(role, prompt));

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.55D);
        options.addProperty("num_predict", maxTokens);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", BridgeConfig.ollamaModel);
        requestBody.addProperty("stream", false);
        requestBody.addProperty("think", false);
        requestBody.add("options", options);
        requestBody.add("messages", messages);

        HttpURLConnection connection = open();
        String raw;
        int code;
        try {
            byte[] encoded = requestBody.toString()
                .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encoded.length);
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encoded);
            }

            code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            raw = stream == null ? "" : read(stream);
        } finally {
            connection.disconnect();
        }
        if (code != HttpURLConnection.HTTP_OK) {
            String detail = raw.length() <= 2000 ? raw : raw.substring(0, 2000) + "…";
            throw new IllegalStateException("Ollama HTTP " + code + ": " + detail);
        }

        String answer = JSON.parse(raw)
            .getAsJsonObject()
            .getAsJsonObject("message")
            .get("content")
            .getAsString();
        answer = cleanAnswer(answer);
        String citation = citation(sources, activeRecipes);
        if (!citation.isEmpty()) answer = answer + "\nИсточник: " + citation;
        remember(role, prompt, answer);
        return answer;
    }

    private static String cleanAnswer(String answer) {
        return answer.replaceAll("(?is)\\s*(?:источник|источники)\\s*:.*$", "")
            .replaceAll("(?is)</?knowledge[^>]*>", "")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .trim();
    }

    private void remember(String role, String prompt, String answer) {
        history.addLast(message(role, prompt));
        history.addLast(message("assistant", answer));
        while (history.size() > 20) history.removeFirst();
    }

    private static boolean looksLikeKnowledgeQuestion(String prompt) {
        String value = prompt.toLowerCase(java.util.Locale.ROOT)
            .trim();
        if (value.matches("^(?:привет|здравствуй|как дела)[!?. ]*$")) return false;
        return value.contains("как ") || value.startsWith("как")
            || value.contains("где ")
            || value.contains("почему")
            || value.contains("зачем")
            || value.contains("сколько")
            || value.contains("рецепт")
            || value.contains("скрафт")
            || value.contains("сделать")
            || value.contains("получить")
            || value.contains("добыть")
            || value.contains("найти")
            || value.contains("существует")
            || value.contains("разве")
            || value.contains("можно ли")
            || value.contains("не договорил")
            || value.contains("продолжи");
    }

    private static String citation(List<KnowledgeSearchResult> sources, String activeRecipes) {
        Set<String> labels = new LinkedHashSet<>();
        if (!activeRecipes.isEmpty()) labels.add("активные рецепты игры");
        for (KnowledgeSearchResult result : sources) {
            String label = result.getArticle()
                .getSourceLabel();
            if (!label.isEmpty()) labels.add(label);
            if (labels.size() >= 2) break;
        }
        return String.join(", ", labels);
    }

    public synchronized void clearHistory() {
        history.clear();
    }

    private static HttpURLConnection open() throws Exception {
        HttpURLConnection connection = OllamaHttp.open("/api/chat", 1500, 120000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        return connection;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static String knowledgeQuery(String prompt, JsonObject context) {
        String lower = prompt.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.contains("это") || lower.contains("этот")
            || lower.contains("рук")
            || lower.contains("передо")
            || lower.contains("смотр"))) return prompt;
        StringBuilder query = new StringBuilder(prompt);
        appendContextName(query, context, "heldItem");
        appendContextName(query, context, "targetBlock");
        return query.toString();
    }

    private static void appendContextName(StringBuilder query, JsonObject context, String field) {
        if (!context.has(field) || !context.get(field)
            .isJsonObject()) return;
        JsonObject object = context.getAsJsonObject(field);
        if (object.has("name")) query.append(' ')
            .append(
                object.get("name")
                    .getAsString());
    }

    private static String read(InputStream stream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
