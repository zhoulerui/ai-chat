package com.example.aichat.dto;

/**
 * 百科条目列表项(含分块数、向量化状态)。
 * 普通 Bean:MyBatis 下划线别名自动转驼峰映射。
 */
public class ArticleItem {
    private Long id;
    private Long gameId;
    private String title;
    private String type;
    private String content;
    private Integer chunkCount;
    private Boolean vectorized;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGameId() { return gameId; }
    public void setGameId(Long gameId) { this.gameId = gameId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public Boolean getVectorized() { return vectorized; }
    public void setVectorized(Boolean vectorized) { this.vectorized = vectorized; }
}
