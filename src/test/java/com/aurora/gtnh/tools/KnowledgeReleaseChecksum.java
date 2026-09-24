package com.aurora.gtnh.tools;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Writes the reproducible SHA-256 sidecar used when publishing a knowledge-profile release. */
public final class KnowledgeReleaseChecksum {

    private KnowledgeReleaseChecksum() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected archive and checksum output paths");
        File archive = new File(args[0]);
        File output = new File(args[1]);
        if (!archive.isFile()) throw new IllegalArgumentException("Knowledge archive does not exist: " + archive);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(archive))) {
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }

        StringBuilder checksum = new StringBuilder();
        for (byte value : digest.digest()) checksum.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8)) {
            writer.write(checksum + "  " + archive.getName() + "\n");
        }
        System.out.println("Knowledge release: " + archive.getAbsolutePath());
        System.out.println("SHA-256: " + checksum);
    }
}
