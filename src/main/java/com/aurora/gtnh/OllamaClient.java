package com.aurora.gtnh;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Talks directly to the loopback Ollama API; no external Python bridge is required. */
public final class OllamaClient {

    private static final JsonParser JSON = new JsonParser();
    private final Deque<JsonObject> history = new ArrayDeque<>();

    public synchronized String answer(String prompt, JsonObject context, List<String> recentChat) throws Exception {
        return complete("user", prompt, context, recentChat, 180);
    }

    public synchronized String reactToEvent(String event, JsonObject context, List<String> recentChat)
        throws Exception {
        String instruction = "Событие игры: " + event
            + " Отреагируй сама одной короткой уместной репликой. Не задавай вопрос и не пересказывай технические поля.";
        return complete("system", instruction, context, recentChat, 80);
    }

    private String complete(String role, String prompt, JsonObject context, List<String> recentChat, int maxTokens)
        throws Exception {
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt(context, recentChat)));
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
        history.addLast(message(role, prompt));
        history.addLast(message("assistant", answer));
        while (history.size() > 20) history.removeFirst();
        return answer;
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

    private static String systemPrompt(JsonObject context, List<String> recentChat) {
        return "Ты Аврора — живая, остроумная подруга и советница игрока в GT New Horizons 2.8.4. "
            + "Отвечай по-русски, тепло и обычно в 1–3 коротких предложениях. Не выдумывай рецепты и игровые "
            + "механики: если точных знаний нет, честно скажи, что не уверена. Не управляй персонажем. "
            + "targetBlock — только блок под прицелом, а не обязательно тема разговора. Текущий снимок: "
            + context.toString()
            + ". Недавний игровой чат: "
            + new ArrayList<>(recentChat).toString();
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
