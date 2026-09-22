package com.aurora.gtnh;

import com.google.gson.JsonObject;

/** A validated catalog entry describing one downloadable external knowledge profile. */
final class KnowledgeProfileDescriptor {

    private static final String TRUSTED_RELEASE_PREFIX = "https://github.com/NasUmaev/aurora-bridges/releases/download/";

    private final String id;
    private final String version;
    private final String minecraftVersion;
    private final String url;
    private final String sha256;

    private KnowledgeProfileDescriptor(String id, String version, String minecraftVersion, String url, String sha256) {
        this.id = id;
        this.version = version;
        this.minecraftVersion = minecraftVersion;
        this.url = url;
        this.sha256 = sha256;
    }

    static KnowledgeProfileDescriptor defaultProfile() {
        return create(
            "vanilla-1.7.10",
            "0.2.0",
            "1.7.10",
            TRUSTED_RELEASE_PREFIX + "knowledge-v0.2.0/vanilla-1.7.10-profile-v0.2.0.zip",
            "e9e0cb68d955135bb73af448b10d53bf3695e5b265d1fa84a00e617b31c8932f");
    }

    static KnowledgeProfileDescriptor fromJson(JsonObject json) {
        return create(
            string(json, "id"),
            string(json, "version"),
            string(json, "minecraftVersion"),
            string(json, "url"),
            string(json, "sha256"));
    }

    private static KnowledgeProfileDescriptor create(String id, String version, String minecraftVersion, String url,
        String sha256) {
        if (!id.matches("[a-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid knowledge profile id");
        if (!version.matches("[0-9]+(?:\\.[0-9]+){0,3}"))
            throw new IllegalArgumentException("Invalid knowledge profile version");
        if (!"1.7.10".equals(minecraftVersion))
            throw new IllegalArgumentException("Incompatible Minecraft knowledge profile");
        if (!url.startsWith(TRUSTED_RELEASE_PREFIX))
            throw new IllegalArgumentException("Untrusted knowledge profile download URL");
        String normalizedHash = sha256.toLowerCase(java.util.Locale.ROOT);
        if (!normalizedHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid knowledge profile checksum");
        return new KnowledgeProfileDescriptor(id, version, minecraftVersion, url, normalizedHash);
    }

    private static String string(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name)
            .isJsonPrimitive()) throw new IllegalArgumentException("Missing catalog field " + name);
        return object.get(name)
            .getAsString();
    }

    String getId() {
        return id;
    }

    String getVersion() {
        return version;
    }

    String getMinecraftVersion() {
        return minecraftVersion;
    }

    String getUrl() {
        return url;
    }

    String getSha256() {
        return sha256;
    }
}
