package com.example.aichat.rag;

/**
 * 检索命中结果(含所属文章的完整正文,便于前端查看上下文)。
 * articleId/articleTitle/articleContent 为 null 表示该 chunk 无关联文章。
 */
public record ChunkHit(Long id, Long gameId, String content, double similarity,
                       Long articleId, String articleTitle, String articleContent) {
}
