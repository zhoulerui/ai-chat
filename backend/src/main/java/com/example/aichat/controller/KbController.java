package com.example.aichat.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.server.ResponseStatusException;

import com.example.aichat.dto.ArticleItem;
import com.example.aichat.entity.Chunk;
import com.example.aichat.entity.Game;
import com.example.aichat.mapper.ArticleMapper;
import com.example.aichat.mapper.GameMapper;
import com.example.aichat.rag.ChunkHit;
import com.example.aichat.rag.RagService;
import com.example.aichat.util.DocParser;
import com.example.aichat.util.UrlImporter;

/**
 * 神谕百科知识库接口:游戏管理、百科条目、文档上传入库、检索测试。
 */
@Tag(name = "知识库", description = "游戏管理 / 条目 / 分块 / 检索 / 网址一键入库")
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private static final Logger log = LoggerFactory.getLogger(KbController.class);

    private final GameMapper gameMapper;
    private final ArticleMapper articleMapper;
    private final RagService ragService;

    public KbController(GameMapper gameMapper, ArticleMapper articleMapper, RagService ragService) {
        this.gameMapper = gameMapper;
        this.articleMapper = articleMapper;
        this.ragService = ragService;
    }

    // ---------- 游戏 ----------

    @Operation(summary = "游戏列表", description = "全部游戏,供问答页下拉选择")
    @GetMapping("/game")
    public List<Game> listGames() {
        return gameMapper.selectGames();
    }

    @Operation(summary = "新建游戏", description = "body: {\"name\": \"原神\"}")
    @PostMapping("/game")
    public Map<String, Object> createGame(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "游戏名不能为空");
        }
        Game g = new Game();
        g.setName(name);
        g.setCategory((String) body.getOrDefault("category", null));
        g.setPlatform((String) body.getOrDefault("platform", null));
        g.setPublisher((String) body.getOrDefault("publisher", null));
        g.setSummary((String) body.getOrDefault("summary", null));
        gameMapper.insertGame(g);
        log.info("创建游戏 #{}:{}", g.getId(), name);
        return Map.of("id", g.getId(), "name", name);
    }

    @Operation(summary = "删除游戏", description = "级联删除其条目/分块")
    @DeleteMapping("/game/{id}")
    public Map<String, Object> deleteGame(@PathVariable Long id) {
        ragService.deleteGame(id);   // 级联删除 + 事务在 Service 层
        return Map.of("ok", true);
    }

    // ---------- 文档上传(P0 支持 .txt / .md 纯文本) ----------

    // ---------- 文档上传(支持 txt/md/pdf/docx/xlsx 等,见 DocParser) ----------

    @Operation(summary = "上传文档入库", description = "multipart: gameId + file + 可选 title;支持 txt/md/pdf/docx/xlsx/csv")
    @PostMapping("/upload")
    public Map<String, Object> upload(
            @RequestParam("gameId") Long gameId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "content", required = false) String content) throws IOException {
        if (file == null && (content == null || content.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供文件或文本内容");
        }
        String text;
        String name;
        if (file != null) {
            String fn = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            try {
                text = DocParser.extract(file.getBytes(), fn);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            name = title == null || title.isBlank() ? fn : title;
        } else {
            text = content;
            name = title == null || title.isBlank() ? ("文本-" + System.currentTimeMillis()) : title;
        }
        log.info("收到上传:gameId={} title=[{}] 文本长度={}", gameId, name, text.length());
        int chunks = ragService.ingest(gameId, name, text);
        return Map.of("ok", true, "chunks", chunks);
    }

    // ---------- 百科条目 ----------

    /** 按游戏查全部条目(含分块数/向量化状态),知识库页主列表 */
    @Operation(summary = "条目列表", description = "按游戏查询,含分块数与已向量化状态")
    @GetMapping("/articles")
    public List<ArticleItem> articles(@RequestParam Long gameId) {
        return articleMapper.selectArticlesByGame(gameId);
    }

    /** 删除条目及其分块(级联,事务在 Service 层) */
    @Operation(summary = "删除条目", description = "级联删除分块并重建内存向量库")
    @DeleteMapping("/article/{id}")
    public Map<String, Object> deleteArticle(@PathVariable Long id) {
        ragService.deleteArticle(id);
        return Map.of("ok", true);
    }

    /** 网页一键入库(SSRF 防护 + 正文提取) */
    @Operation(summary = "网址一键入库", description = "body: {gameId, url, title?};仅公网 http/https(SSRF 防护),自动提取标题与正文")
    @PostMapping("/import-url")
    public Map<String, Object> importUrl(@RequestBody Map<String, Object> body) throws IOException {
        Long gameId = body.get("gameId") == null ? null : Long.valueOf(body.get("gameId").toString());
        String url = (String) body.get("url");
        String title = (String) body.get("title");
        if (gameId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 gameId");
        }
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少网址");
        }
        try {
            UrlImporter.Result r = UrlImporter.importUrl(url.trim());
            String name = (title == null || title.isBlank()) ? r.title() : title.trim();
            int chunks = ragService.ingest(gameId, name, r.text());
            log.info("URL 入库:gameId={} url=[{}] title=[{}] 文本 {} 字,分块 {}", gameId, url, name, r.text().length(), chunks);
            return Map.of("ok", true, "title", name, "chars", r.text().length(), "chunks", chunks);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ---------- chunk ----------

    @Operation(summary = "分块列表", description = "可选按 gameId 过滤")
    @GetMapping("/chunks")
    public List<Chunk> chunks(@RequestParam(required = false) Long gameId) {
        return ragService.listChunks(gameId);
    }

    @Operation(summary = "删除分块", description = "删除单个分块并重建向量库(重传时用)")
    @DeleteMapping("/chunk/{id}")
    public Map<String, Object> deleteChunk(@PathVariable Long id) {
        ragService.deleteChunk(id);
        return Map.of("ok", true);
    }

    @Operation(summary = "检索测试", description = "body: {gameId, query, topK};返回命中分块+相似度+完整正文回溯")
    @PostMapping("/search")
    public List<ChunkHit> search(@RequestBody Map<String, Object> body) {
        Long gameId = body.get("gameId") == null ? null : Long.valueOf(body.get("gameId").toString());
        String query = (String) body.getOrDefault("query", "");
        int topK = body.get("topK") == null ? 4 : Integer.parseInt(body.get("topK").toString());
        return ragService.search(gameId, query, topK);
    }
}
