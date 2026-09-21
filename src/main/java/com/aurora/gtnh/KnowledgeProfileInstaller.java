package com.aurora.gtnh;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Installs a versioned knowledge profile as data beside the mod, never inside the mod JAR. */
final class KnowledgeProfileInstaller {

    private static final long MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 32L * 1024L * 1024L;
    private static final JsonParser JSON = new JsonParser();
    private final KnowledgeProfileDescriptor profile;

    KnowledgeProfileInstaller() {
        this(KnowledgeProfileDescriptor.defaultProfile());
    }

    KnowledgeProfileInstaller(KnowledgeProfileDescriptor profile) {
        this.profile = profile;
    }

    void install(AuroraInstaller.ProgressListener listener) throws Exception {
        File root = AuroraRuntimeManager.auroraDirectory();
        File downloads = new File(root, "downloads");
        File profiles = KnowledgeRepository.profilesDirectory();
        downloads.mkdirs();
        profiles.mkdirs();

        File archive = new File(downloads, profile.getId() + "-profile-v" + profile.getVersion() + ".zip.part");
        listener.update("Скачиваю профиль " + profile.getId() + "…", 0.92D);
        download(archive, listener);
        verifyChecksum(archive);

        File staging = new File(profiles, "." + profile.getId() + ".installing");
        deleteInside(profiles, staging);
        if (!staging.mkdirs()) throw new IllegalStateException("Не удалось подготовить папку профиля");
        extract(archive, staging);

        File extracted = new File(staging, profile.getId());
        File manifestFile = new File(extracted, "manifest.json");
        if (!manifestFile.isFile()) {
            throw new IllegalStateException("В архиве отсутствует manifest.json профиля");
        }
        validateManifest(manifestFile);
        File destination = new File(profiles, profile.getId());
        File previous = new File(profiles, "." + profile.getId() + ".previous");
        deleteInside(profiles, previous);
        if (destination.exists() && !destination.renameTo(previous)) {
            throw new IllegalStateException("Не удалось обновить предыдущий профиль");
        }
        if (!extracted.renameTo(destination)) {
            if (previous.exists()) previous.renameTo(destination);
            throw new IllegalStateException("Не удалось активировать профиль знаний");
        }
        deleteInside(profiles, staging);
        deleteInside(profiles, previous);
        archive.delete();
        listener.update("Профиль Minecraft 1.7.10 установлен", 0.99D);
    }

    private void download(File target, AuroraInstaller.ProgressListener listener) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(profile.getUrl()).openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "AuroraGTNH/0.1");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("Knowledge profile download HTTP " + code);
            }
            long declared = connection.getContentLengthLong();
            if (declared > MAX_ARCHIVE_BYTES) throw new IllegalStateException("Профиль знаний слишком большой");
            long downloaded = 0L;
            byte[] buffer = new byte[32 * 1024];
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    downloaded += count;
                    if (downloaded > MAX_ARCHIVE_BYTES)
                        throw new IllegalStateException("Профиль знаний слишком большой");
                    output.write(buffer, 0, count);
                    double fraction = declared > 0L ? (double) downloaded / (double) declared : 0.0D;
                    listener.update("Скачиваю профиль " + profile.getId() + "…", 0.92D + fraction * 0.04D);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void verifyChecksum(File archive) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        try (InputStream input = new BufferedInputStream(new FileInputStream(archive))) {
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        if (!profile.getSha256()
            .equals(actual.toString())) {
            throw new SecurityException("Контрольная сумма профиля знаний не совпала");
        }
    }

    private void validateManifest(File manifestFile) throws Exception {
        JsonObject manifest;
        try (InputStream input = new BufferedInputStream(new FileInputStream(manifestFile))) {
            manifest = JSON.parse(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
        String id = manifest.has("id") ? manifest.get("id")
            .getAsString() : "";
        String version = manifest.has("profileVersion") ? manifest.get("profileVersion")
            .getAsString() : "";
        String minecraftVersion = manifest.has("minecraftVersion") ? manifest.get("minecraftVersion")
            .getAsString() : "";
        if (!profile.getId()
            .equals(id)
            || !profile.getVersion()
                .equals(version)
            || !profile.getMinecraftVersion()
                .equals(minecraftVersion)) {
            throw new SecurityException("Манифест профиля не совпадает с онлайн-каталогом");
        }
    }

    private static void extract(File archive, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        long expanded = 0L;
        byte[] buffer = new byte[32 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(destination, entry.getName());
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new SecurityException("Недопустимый путь внутри профиля");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IllegalStateException("Не удалось создать папку профиля");
                    }
                    continue;
                }
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IllegalStateException("Не удалось создать папку профиля");
                }
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    int count;
                    while ((count = zip.read(buffer)) >= 0) {
                        expanded += count;
                        if (expanded > MAX_EXPANDED_BYTES) {
                            throw new IllegalStateException("Распакованный профиль знаний слишком большой");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private static void deleteInside(File allowedRoot, File target) throws Exception {
        String root = allowedRoot.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath()
            .startsWith(root)) throw new SecurityException("Отказано в удалении пути вне profiles");
        if (!target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) deleteInside(allowedRoot, child);
        }
        if (!target.delete()) throw new IllegalStateException("Не удалось удалить " + target);
    }
}
