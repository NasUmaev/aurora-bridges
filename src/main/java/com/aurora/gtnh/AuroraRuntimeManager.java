package com.aurora.gtnh;

import java.io.File;
import java.net.HttpURLConnection;

import net.minecraft.client.Minecraft;

/** Owns the optional portable Ollama process stored inside the Minecraft instance. */
public final class AuroraRuntimeManager {

    private static final long MAX_LOG_SIZE = 5L * 1024L * 1024L;

    public enum Status {
        STARTING,
        READY,
        NEEDS_INSTALL,
        FAILED
    }

    private volatile Status status = Status.STARTING;
    private volatile String detail = "";
    private Process ownedProcess;

    public AuroraRuntimeManager() {
        auroraDirectory().mkdirs();
        Runtime.getRuntime()
            .addShutdownHook(new Thread(this::stop, "Aurora-Runtime-Shutdown"));
    }

    public synchronized boolean ensureRunning() {
        if (isApiReady()) {
            status = Status.READY;
            detail = "";
            return true;
        }
        if (ownedProcess != null && ownedProcess.isAlive()) return waitUntilReady();

        File executable = portableExecutable();
        boolean portable = executable.isFile() && executable.canExecute();
        if (!portable) executable = systemExecutable();
        if (executable == null || !executable.isFile() || !executable.canExecute()) {
            status = Status.NEEDS_INSTALL;
            detail = "Ollama runtime is not installed";
            return false;
        }

        status = Status.STARTING;
        File root = auroraDirectory();
        File models = new File(root, "models");
        File logs = new File(root, "logs");
        models.mkdirs();
        logs.mkdirs();
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "serve");
            builder.directory(root);
            if (portable) {
                builder.environment()
                    .put("OLLAMA_MODELS", models.getAbsolutePath());
            }
            rotateLog(new File(logs, "ollama.log"));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logs, "ollama.log")));
            ownedProcess = builder.start();
            return waitUntilReady();
        } catch (Exception exception) {
            status = Status.FAILED;
            detail = exception.toString();
            AuroraBridgeMod.LOG.error("Could not start portable Ollama", exception);
            return false;
        }
    }

    public Status getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public synchronized void stop() {
        if (ownedProcess != null) {
            ownedProcess.destroy();
            ownedProcess = null;
        }
    }

    public static File auroraDirectory() {
        return new File(Minecraft.getMinecraft().mcDataDir, "aurora");
    }

    private boolean waitUntilReady() {
        for (int attempt = 0; attempt < 60; attempt++) {
            if (isApiReady()) {
                status = Status.READY;
                detail = "";
                return true;
            }
            if (ownedProcess != null && !ownedProcess.isAlive()) break;
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                break;
            }
        }
        status = Status.FAILED;
        detail = "Ollama did not become ready";
        return false;
    }

    private static boolean isApiReady() {
        HttpURLConnection connection = null;
        try {
            connection = OllamaHttp.open("/api/tags", 500, 750);
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void rotateLog(File log) {
        if (!log.isFile() || log.length() <= MAX_LOG_SIZE) return;
        File previous = new File(log.getParentFile(), log.getName() + ".1");
        if (previous.exists() && !previous.delete()) {
            AuroraBridgeMod.LOG.warn("Could not remove old Ollama log {}", previous);
            return;
        }
        if (!log.renameTo(previous)) AuroraBridgeMod.LOG.warn("Could not rotate Ollama log {}", log);
    }

    static File portableExecutable() {
        File root = new File(auroraDirectory(), "runtime");
        if (System.getProperty("os.name", "")
            .toLowerCase()
            .contains("mac")) {
            return new File(root, "Ollama.app/Contents/Resources/ollama");
        }
        return new File(root, "ollama");
    }

    private static File systemExecutable() {
        String home = System.getProperty("user.home", "");
        String[] candidates = { "/opt/homebrew/bin/ollama", "/usr/local/bin/ollama", "/opt/local/bin/ollama",
            "/Applications/Ollama.app/Contents/Resources/ollama",
            home + "/Applications/Ollama.app/Contents/Resources/ollama" };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile() && file.canExecute()) return file;
        }
        return null;
    }
}
