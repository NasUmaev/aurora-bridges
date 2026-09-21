package com.aurora.gtnh;

import com.google.gson.JsonObject;

/** A semantic game event that can be remembered even when Aurora chooses not to comment on it. */
final class AuroraEvent {

    private final String type;
    private final String summary;
    private final String importance;
    private final boolean reactionRecommended;
    private final JsonObject data;

    AuroraEvent(String type, String summary, String importance, boolean reactionRecommended) {
        this(type, summary, importance, reactionRecommended, null);
    }

    AuroraEvent(String type, String summary, String importance, boolean reactionRecommended, JsonObject data) {
        this.type = type;
        this.summary = summary;
        this.importance = importance;
        this.reactionRecommended = reactionRecommended;
        this.data = data;
    }

    String getType() {
        return type;
    }

    String getSummary() {
        return summary;
    }

    String getImportance() {
        return importance;
    }

    boolean isReactionRecommended() {
        return reactionRecommended;
    }

    JsonObject getData() {
        return data;
    }
}
