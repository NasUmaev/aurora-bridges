package com.aurora.gtnh;

import java.util.Collections;
import java.util.List;

final class KnowledgeArticle {

    private final String profile;
    private final String id;
    private final String title;
    private final List<String> aliases;
    private final List<String> tags;
    private final String body;
    private final String sourceLabel;
    private final String sourceUrl;

    KnowledgeArticle(String profile, String id, String title, List<String> aliases, List<String> tags, String body,
        String sourceLabel, String sourceUrl) {
        this.profile = profile;
        this.id = id;
        this.title = title;
        this.aliases = Collections.unmodifiableList(aliases);
        this.tags = Collections.unmodifiableList(tags);
        this.body = body;
        this.sourceLabel = sourceLabel;
        this.sourceUrl = sourceUrl;
    }

    String getProfile() {
        return profile;
    }

    String getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    List<String> getAliases() {
        return aliases;
    }

    List<String> getTags() {
        return tags;
    }

    String getBody() {
        return body;
    }

    String getSourceLabel() {
        return sourceLabel;
    }

    String getSourceUrl() {
        return sourceUrl;
    }
}
