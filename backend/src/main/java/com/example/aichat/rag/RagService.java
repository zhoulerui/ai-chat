package com.example.aichat.rag;

import java.util.List;

import com.example.aichat.entity.Chunk;

/**
 * RAG 知识库服务接口。
 * 实现:com.example.aichat.rag.RagServiceImpl
 */
public interface RagService {

    /** 上传文本入库:分块 → 嵌入 → 存数据库 → 加入内存向量库 */
    int ingest(Long gameId, String title, String content);

    /** 语义检索:先取 TopK*3,再按游戏过滤取前 TopK(bge 查询需加官方前缀) */
    List<ChunkHit> search(Long gameId, String query, int topK);

    /** 构造 RAG 系统提示:检索结果拼进 SystemMessage;无相关则返回 null */
    String buildSystemPrompt(Long gameId, String question);

    /** 将检索命中拼接为系统提示(供 ChatController 复用,便于同时把来源发给前端) */
    String buildPromptFromHits(List<ChunkHit> hits);

    void deleteChunk(Long id);

    /** 删除游戏及其全部知识(级联,事务) */
    void deleteGame(Long id);

    /** 删除单个百科条目及其全部分块(级联,事务) */
    void deleteArticle(Long id);

    List<Chunk> listChunks(Long gameId);
}
