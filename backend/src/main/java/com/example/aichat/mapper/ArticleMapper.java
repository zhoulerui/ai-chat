package com.example.aichat.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.example.aichat.dto.ArticleItem;
import com.example.aichat.entity.Article;

@Mapper
public interface ArticleMapper {

    int insertArticle(Article article);

    /** 按游戏查全部条目(带分块数与向量化状态) */
    List<ArticleItem> selectArticlesByGame(@Param("gameId") Long gameId);

    int deleteArticle(@Param("id") Long id);
}
