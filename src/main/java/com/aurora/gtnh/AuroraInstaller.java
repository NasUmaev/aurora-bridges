package com.aurora.gtnh;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Downloads the official macOS Ollama app and a local model after explicit in-game consent. */
public final class AuroraInstaller {

    public interface ProgressListener {

        void update(String status, double progress);
    }

    private static final String OLLAMA_DOWNLOAD = "https://ollama.com/download/Ollama-darwin.zip";
    private static final long MAX_RUNTIME_DOWNLOAD = 2L * 1024L * 1024L * 1024L;
    private static final JsonParser JSON = new JsonParser();

    private final AuroraRuntimeManager runtime;

    public AuroraInstaller(AuroraRuntimeManager runtime) {
        this.runtime = runtime;
    }

    public void install(ProgressListener listener) throws Exception {
        if (!System.getProperty("os.name", "")
            .toLowerCase()
            .contains("mac")) {
            throw new IllegalStateException("Автоматическая установка пока поддерживает только macOS");
        }

        File root = AuroraRuntimeManager.auroraDirectory();
        File downloads = new File(root, "downloads");
        File runtimeDirectory = new File(root, "runtime");
        downloads.mkdirs();
        runtimeDirectory.mkdirs();

        File archive = new File(downloads, "Ollama-darwin.zip.part");
        listener.update("Скачиваю официальный Ollama runtime…", 0.01D);
        downloadRuntime(archive, listener);

        listener.update("Распаковываю Ollama…", 0.36D);
        extractWithDitto(archive, runtimeDirectory);
        verifySignature(new File(runtimeDirectory, "Ollama.app"));
        File executable = AuroraRuntimeManager.portableExecutable();
        if (!executable.setExecutable(true) && !executable.canExecute()) {
            throw new IllegalStateException("Не удалось разрешить запуск Ollama");
        }

        listener.update("Запускаю локальную Ollama…", 0.42D);
        if (!runtime.ensureRunning()) throw new IllegalStateException(runtime.getDetail());

        listener.update("Скачиваю модель " + BridgeConfig.ollamaModel + "…", 0.45D);
        pullModel(listener);
        archive.delete();
        listener.update("Аврора готова!", 1.0D);
    }

    private static void downloadRuntime(File target, ProgressListener listener) throws Exception {
        long existing = target.isFile() ? target.length() : 0L;
        HttpURLConnection connection = (HttpURLConnection) new URL(OLLAMA_DOWNLOAD).openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "AuroraGTNH/0.1");
            if (existing > 0L) connection.setRequestProperty("Range", "bytes=" + existing + "-");

            int code = connection.getResponseCode();
            boolean append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L;
            if (code != HttpURLConnection.HTTP_OK && !append) {
                throw new IllegalStateException("Ollama download HTTP " + code);
            }
            if (!append) existing = 0L;
            long remaining = connection.getContentLengthLong();
            long total = remaining > 0L ? existing + remaining : -1L;
            if (total > MAX_RUNTIME_DOWNLOAD) throw new IllegalStateException("Ollama runtime is unexpectedly large");

            long downloaded = existing;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = connection.getInputStream();
                OutputStream output = new FileOutputStream(target, append)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                    downloaded += count;
                    if (downloaded > MAX_RUNTIME_DOWNLOAD)
                        throw new IllegalStateException("Ollama download limit exceeded");
                    double fraction = total > 0L ? (double) downloaded / (double) total : 0.0D;
                    listener.update("Скачиваю Ollama: " + megabytes(downloaded) + " МБ", 0.01D + fraction * 0.34D);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void extractWithDitto(File archive, File destination) throws Exception {
        Process process = new ProcessBuilder(
            "/usr/bin/ditto",
            "-x",
            "-k",
            archive.getAbsolutePath(),
            destination.getAbsolutePath()).redirectErrorStream(true)
                .start();
        String output = read(process.getInputStream());
        if (process.waitFor() != 0) throw new IllegalStateException("Не удалось распаковать Ollama: " + output);
    }

    private static void verifySignature(File app) throws Exception {
        if (!app.isDirectory()) throw new IllegalStateException("В архиве отсутствует Ollama.app");
        Process process = new ProcessBuilder(
            "/usr/bin/codesign",
            "--verify",
            "--deep",
            "--strict",
            app.getAbsolutePath()).redirectErrorStream(true)
                .start();
        String output = read(process.getInputStream());
        if (process.waitFor() != 0) throw new SecurityException("Подпись Ollama не прошла проверку: " + output);
    }

    private static void pullModel(ProgressListener listener) throws Exception {
        HttpURLConnection connection = OllamaHttp.open("/api/pull", 5000, 30 * 60 * 1000);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            JsonObject body = new JsonObject();
            body.addProperty("model", BridgeConfig.ollamaModel);
            body.addProperty("stream", true);
            byte[] encoded = body.toString()
                .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encoded);
            }

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                String error = connection.getErrorStream() == null ? "" : read(connection.getErrorStream());
                throw new IllegalStateException("Model download HTTP " + code + ": " + error);
            }
            boolean complete = false;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonObject event = JSON.parse(line)
                        .getAsJsonObject();
                    if (event.has("error")) throw new IllegalStateException(
                        event.get("error")
                            .getAsString());
                    String status = event.has("status") ? event.get("status")
                        .getAsString() : "Скачиваю модель";
                    long total = event.has("total") ? event.get("total")
                        .getAsLong() : 0L;
                    long completed = event.has("completed") ? event.get("completed")
                        .getAsLong() : 0L;
                    double modelProgress = total > 0L ? (double) completed / (double) total : 0.0D;
                    listener.update(status, 0.45D + modelProgress * 0.54D);
                    if ("success".equalsIgnoreCase(status)) complete = true;
                }
            }
            if (!complete) throw new IllegalStateException("Ollama не подтвердила завершение загрузки модели");
        } finally {
            connection.disconnect();
        }
    }

    private static long megabytes(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private static String read(InputStream stream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line)
                .append('\n');
        }
        return result.toString()
            .trim();
    }
}
