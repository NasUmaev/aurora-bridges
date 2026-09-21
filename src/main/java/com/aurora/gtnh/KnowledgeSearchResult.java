package com.aurora.gtnh;

final class KnowledgeSearchResult {

    private final KnowledgeArticle article;
    private final int score;

    KnowledgeSearchResult(KnowledgeArticle article, int score) {
        this.article = article;
        this.score = score;
    }

    KnowledgeArticle getArticle() {
        return article;
    }

    int getScore() {
        return score;
    }
}
