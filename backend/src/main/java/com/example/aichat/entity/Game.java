package com.example.aichat.entity;

import java.time.LocalDate;

/** 游戏(game 表) */
public class Game {
    private Long id;
    private String name;
    private String category;
    private String platform;
    private String publisher;
    private LocalDate releaseDate;
    private String summary;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = publisher; }
    public LocalDate getReleaseDate() { return releaseDate; }
    public void setReleaseDate(LocalDate releaseDate) { this.releaseDate = releaseDate; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
