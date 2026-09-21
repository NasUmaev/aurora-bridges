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

/** Installs a versioned knowledge profile as data beside the mod, never inside the mod JAR. */
final class KnowledgeProfileInstaller {

    private static final String PROFILE_ID = "vanilla-1.7.10";
    private static final String PROFILE_URL = "https://github.com/NasUmaev/aurora-bridges/releases/download/"
        + "knowledge-v0.1.0/vanilla-1.7.10-profile-v0.1.0.zip";
    private static final String PROFILE_SHA256 = "22dc864488f31a461f79f36872ea4c3ab8e6316a61c74021ecf9c549e4ad2a7f";
    private static final long MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 32L * 1024L * 1024L;

    void install(AuroraInstaller.ProgressListener listener) throws Exception {
        File root = AuroraRuntimeManager.auroraDirectory();
        File downloads = new File(root, "downloads");
        File profiles = KnowledgeRepository.profilesDirectory();
        downloads.mkdirs();
        profiles.mkdirs();

        File archive = new File(downloads, "vanilla-1.7.10-profile-v0.1.0.zip.part");
        listener.update("Скачиваю профиль Minecraft 1.7.10…", 0.92D);
        download(archive, listener);
        verifyChecksum(archive);

        File staging = new File(profiles, ".vanilla-1.7.10.installing");
        deleteInside(profiles, staging);
        if (!staging.mkdirs()) throw new IllegalStateException("Не удалось подготовить папку профиля");
        extract(archive, staging);

        File extracted = new File(staging, PROFILE_ID);
        if (!new File(extracted, "manifest.json").isFile()) {
            throw new IllegalStateException("В архиве отсутствует manifest.json профиля");
        }
        File destination = new File(profiles, PROFILE_ID);
        File previous = new File(profiles, ".vanilla-1.7.10.previous");
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

    private static void download(File target, AuroraInstaller.ProgressListener listener) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(PROFILE_URL).openConnection();
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
                    listener.update("Скачиваю профиль Minecraft 1.7.10…", 0.92D + fraction * 0.04D);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void verifyChecksum(File archive) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        try (InputStream input = new BufferedInputStream(new FileInputStream(archive))) {
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        if (!PROFILE_SHA256.equals(actual.toString())) {
            throw new SecurityException("Контрольная сумма профиля знаний не совпала");
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
