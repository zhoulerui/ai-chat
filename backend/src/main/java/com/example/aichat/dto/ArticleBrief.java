package com.example.aichat.dto;

/**
 * chunk 所属文章摘要(chunk→article 关联查询结果)。
 * 用普通 Bean(而非 record):MyBatis 按 setter 映射,下划线别名自动转驼峰。
 */
public class ArticleBrief {
    private Long chunkId;
    private Long articleId;
    private String title;
    private String content;

    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public Long getArticleId() { return articleId; }
    public void setArticleId(Long articleId) { this.articleId = articleId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
