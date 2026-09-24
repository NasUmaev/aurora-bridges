package com.aurora.gtnh;

import static org.junit.Assert.assertEquals;

import java.io.FileReader;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class KnowledgeReleaseMetadataTest {

    private static final JsonParser JSON = new JsonParser();

    @Test
    public void bootstrapCatalogAndSourceProfileStayInSync() throws Exception {
        JsonObject manifest;
        try (FileReader reader = new FileReader("knowledge-packs/vanilla-1.7.10/manifest.json")) {
            manifest = JSON.parse(reader)
                .getAsJsonObject();
        }
        JsonObject catalog;
        try (FileReader reader = new FileReader("knowledge-catalog/catalog.json")) {
            catalog = JSON.parse(reader)
                .getAsJsonObject()
                .getAsJsonArray("profiles")
                .get(0)
                .getAsJsonObject();
        }

        KnowledgeProfileDescriptor bootstrap = KnowledgeProfileDescriptor.defaultProfile();
        KnowledgeProfileDescriptor published = KnowledgeProfileDescriptor.fromJson(catalog);
        assertEquals(
            manifest.get("id")
                .getAsString(),
            bootstrap.getId());
        assertEquals(
            manifest.get("profileVersion")
                .getAsString(),
            bootstrap.getVersion());
        assertEquals(
            manifest.get("minecraftVersion")
                .getAsString(),
            bootstrap.getMinecraftVersion());
        assertEquals(published.getId(), bootstrap.getId());
        assertEquals(published.getVersion(), bootstrap.getVersion());
        assertEquals(published.getMinecraftVersion(), bootstrap.getMinecraftVersion());
        assertEquals(published.getUrl(), bootstrap.getUrl());
        assertEquals(published.getSha256(), bootstrap.getSha256());
    }
}
