package com.example.aichat.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.aichat.config.LocalBgeEmbeddingModel;
import com.example.aichat.dto.ArticleBrief;
import com.example.aichat.entity.Article;
import com.example.aichat.entity.Chunk;
import com.example.aichat.mapper.ArticleMapper;
import com.example.aichat.mapper.ChunkMapper;
import com.example.aichat.mapper.GameMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * RAG 核心服务实现(手动拼接版,便于理解与调优)。
 * 持久化:MyBatis(MySQL/H2);检索:SimpleVectorStore 内存余弦。
 * 嵌入:本地 ONNX bge-small-zh(512 维)。
 */
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private static final Logger log = LoggerFactory.getLogger(RagServiceImpl.class);

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final int TOP_K = 4;

    private final LocalBgeEmbeddingModel embeddingModel;
    private final ChunkMapper chunkMapper;
    private final ArticleMapper articleMapper;
    private final GameMapper gameMapper;
    private final ObjectMapper mapper = new ObjectMapper();   // 自带初始化,不参与注入

    private SimpleVectorStore store;

    /** 启动时从数据库加载全量分块进内存向量库(重启不丢知识) */
    @PostConstruct
    public void init() {
        rebuildStore();
    }

    private void rebuildStore() {
        store = SimpleVectorStore.builder(embeddingModel).build();
        List<Chunk> rows = chunkMapper.selectAllChunks();
        List<Document> docs = new ArrayList<>();
        for (Chunk c : rows) {
            docs.add(new Document(c.getContent(), Map.of(
                    "gameId", String.valueOf(c.getGameId()),
                    "chunkId", String.valueOf(c.getId()))));
        }
        if (!docs.isEmpty()) {
            store.add(docs);
        }
        log.info("知识库加载完成:共 {} 个分块进入内存向量库", rows.size());
    }

    /**
     * 上传文本入库:分块 → 嵌入 → 存数据库 → 加入内存向量库。
     * 事务:DB 写入全部成功才提交;内存向量库的更新放到事务提交后(afterCommit),
     * 避免"DB 回滚但内存已变更"的不一致。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int ingest(Long gameId, String title, String content) {
        Article article = new Article();
        article.setGameId(gameId);
        article.setTitle(title);
        article.setType("upload");
        article.setContent(content);
        articleMapper.insertArticle(article);

        List<String> chunks = chunkText(content);
        List<Map.Entry<String, Long>> pending = new ArrayList<>();
        int count = 0;
        for (String chunk : chunks) {
            float[] vec = embeddingModel.embed(chunk);
            Chunk row = new Chunk();
            row.setGameId(gameId);
            row.setArticleId(article.getId());
            row.setContent(chunk);
            row.setEmbedding(toJson(vec));
            chunkMapper.insertChunk(row);
            pending.add(Map.entry(chunk, row.getId()));
            count++;
        }

        int total = count;
        afterCommit(() -> {
            for (var entry : pending) {
                store.add(List.of(new Document(entry.getKey(), Map.of(
                        "gameId", String.valueOf(gameId),
                        "chunkId", String.valueOf(entry.getValue())))));
            }
            log.info("入库完成(已提交):游戏[{}] 条目[{}],共生成 {} 个分块", gameId, title, total);
        });
        return count;
    }

    @Override
    public List<ChunkHit> search(Long gameId, String query, int topK) {
        List<Document> docs = store.similaritySearch(
                SearchRequest.builder().query(LocalBgeEmbeddingModel.QUERY_PREFIX + query).topK(topK * 3).build());
        List<ChunkHit> hits = docs.stream()
                .filter(d -> gameId == null
                        || gameId.toString().equals(d.getMetadata().get("gameId")))
                .limit(topK)
                .map(d -> new ChunkHit(
                        Long.parseLong((String) d.getMetadata().get("chunkId")),
                        Long.parseLong((String) d.getMetadata().get("gameId")),
                        d.getText(),
                        d.getScore() == null ? 0.0 : d.getScore(),
                        null, null, null))
                .toList();
        // 批量补全命中 chunk 所属文章的完整正文(检索回溯上下文)
        if (!hits.isEmpty()) {
            List<Long> ids = hits.stream().map(ChunkHit::id).toList();
            Map<Long, ArticleBrief> articleByChunk = chunkMapper.selectArticleByChunkIds(ids).stream()
                    .collect(Collectors.toMap(ArticleBrief::getChunkId, b -> b));
            hits = hits.stream()
                    .map(h -> {
                        ArticleBrief a = articleByChunk.get(h.id());
                        if (a == null) {
                            return h;
                        }
                        return new ChunkHit(h.id(), h.gameId(), h.content(), h.similarity(),
                                a.getArticleId(), a.getTitle(), a.getContent());
                    })
                    .toList();
        }
        log.info("检索:gameId={} 问题[{}],命中 {} 条", gameId, query, hits.size());
        return hits;
    }

    @Override
    public String buildSystemPrompt(Long gameId, String question) {
        List<ChunkHit> hits = search(gameId, question, TOP_K);
        return hits.isEmpty() ? null : buildPromptFromHits(hits);
    }

    @Override
    public String buildPromptFromHits(List<ChunkHit> hits) {
        StringBuilder sb = new StringBuilder(
                "以下是与问题相关的游戏资料,请严格基于这些资料回答;资料中没有的信息,请如实说明不知道:\n");
        int i = 1;
        for (ChunkHit h : hits) {
            sb.append("\n【资料").append(i++).append("】").append(h.content());
        }
        return sb.toString();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteChunk(Long id) {
        chunkMapper.deleteChunk(id);
        afterCommit(() -> {
            rebuildStore();
            log.info("删除分块 #{} 完成(已提交)", id);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteArticle(Long id) {
        chunkMapper.deleteChunksByArticle(id);
        articleMapper.deleteArticle(id);
        afterCommit(() -> {
            rebuildStore();
            log.info("删除条目 #{} 及其分块完成(已提交)", id);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteGame(Long id) {
        gameMapper.deleteChunksByGame(id);
        gameMapper.deleteArticlesByGame(id);
        gameMapper.deleteGame(id);
        afterCommit(() -> {
            rebuildStore();
            log.info("删除游戏 #{} 及其全部知识完成(已提交)", id);
        });
    }

    @Override
    public List<Chunk> listChunks(Long gameId) {
        if (gameId == null) {
            return chunkMapper.selectAllChunks().stream().limit(200).toList();
        }
        return chunkMapper.selectChunksByGame(gameId);
    }

    // ---------- 私有工具 ----------

    /** 注册事务提交后回调:DB 提交成功后再更新内存向量库,保证一致性 */
    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private String toJson(float[] vec) {
        try {
            return mapper.writeValueAsString(vec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 简单分块:按字符数切块 + 重叠,避免语义割裂 */
    private List<String> chunkText(String text) {
        String t = text.replace("\r\n", "\n").trim();
        List<String> chunks = new ArrayList<>();
        int len = t.length();
        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        for (int i = 0; i < len; i += step) {
            int end = Math.min(i + CHUNK_SIZE, len);
            String c = t.substring(i, end).trim();
            if (!c.isEmpty()) {
                chunks.add(c);
            }
            if (end == len) {
                break;
            }
        }
        return chunks;
    }
}
