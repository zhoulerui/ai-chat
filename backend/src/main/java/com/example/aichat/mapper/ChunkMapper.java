package com.example.aichat.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.example.aichat.dto.ArticleBrief;
import com.example.aichat.entity.Chunk;

@Mapper
public interface ChunkMapper {

    /** 全量分块(启动时加载进内存向量库) */
    List<Chunk> selectAllChunks();

    List<Chunk> selectChunksByGame(@Param("gameId") Long gameId);

    int insertChunk(Chunk chunk);

    int deleteChunk(@Param("id") Long id);

    /** 删除某条目下的全部分块(级联删除用) */
    int deleteChunksByArticle(@Param("articleId") Long articleId);

    /** 按 chunk id 批量查所属文章,用于检索回溯完整正文 */
    List<ArticleBrief> selectArticleByChunkIds(@Param("ids") List<Long> ids);
}
