package com.example.aichat.tool;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.example.aichat.entity.Game;
import com.example.aichat.mapper.GameMapper;
import com.example.aichat.rag.ChunkHit;
import com.example.aichat.rag.RagService;

import lombok.RequiredArgsConstructor;

/**
 * Agent 工具(Function Calling):让大模型在回答时能主动调用真实业务能力。
 * 由 config/ToolConfig 注册为 ToolCallbackProvider,经 ChatController 的
 * ToolCallingChatOptions 挂到每次流式问答上;模型按需调用,Spring AI 1.0
 * 在流式过程中自动执行工具并继续生成(工具调用循环)。
 *
 * 工具清单:
 *  - searchKnowledgeBase:按需检索游戏知识库(RAG 工具化,多轮追问时补充依据)
 *  - listGames:列出已有游戏
 *  - getGameInfo:查询游戏基本信息
 */
@Component
@RequiredArgsConstructor
public class GameTools {

    private final RagService ragService;
    private final GameMapper gameMapper;

    /**
     * 按需检索知识库:传入游戏名称与具体问题,返回与问题相关的资料片段(含相关度)。
     * 用于回答角色配队、玩法机制、攻略等需要资料依据的问题,或在多轮追问时补充检索。
     */
    @Tool(description = "查询游戏知识库:传入游戏名称和具体问题,返回知识库中与该问题相关的资料片段(带相关度)。"
            + "适用于角色配队、玩法机制、攻略细节等需要资料依据的问题。"
            + "参数 gameName 为游戏名称(如\"原神\"),query 为要查的问题。")
    public String searchKnowledgeBase(String gameName, String query) {
        Game game = findGame(gameName);
        if (game == null) {
            return "知识库中没有名为[" + gameName + "]的游戏,可调用 listGames 查看已有游戏列表。";
        }
        List<ChunkHit> hits = ragService.search(game.getId(), query, 3);
        if (hits.isEmpty()) {
            return "知识库中未找到与[" + query + "]相关的资料。";
        }
        return ragService.buildPromptFromHits(hits);
    }

    /** 列出知识库中已有的全部游戏及简介。 */
    @Tool(description = "列出知识库中已有的全部游戏名称与简介,用于回答\"有哪些游戏\"之类的总览问题。")
    public String listGames() {
        List<Game> games = gameMapper.selectGames();
        if (games.isEmpty()) {
            return "知识库中还没有任何游戏。";
        }
        StringBuilder sb = new StringBuilder("知识库中已有的游戏:\n");
        for (Game g : games) {
            sb.append("- ").append(g.getName());
            if (g.getSummary() != null && !g.getSummary().isBlank()) {
                sb.append(": ").append(g.getSummary());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 查询游戏基本信息(类型/平台/发行商/发售日/简介)。 */
    @Tool(description = "查询游戏的基本信息(类型、平台、发行商、发售日期、简介),参数为游戏名称。")
    public String getGameInfo(String gameName) {
        Game game = findGame(gameName);
        if (game == null) {
            return "知识库中没有名为[" + gameName + "]的游戏,可调用 listGames 查看已有游戏列表。";
        }
        return String.format(
                "游戏:%s\n类型:%s\n平台:%s\n发行商:%s\n发售日期:%s\n简介:%s",
                game.getName(),
                nz(game.getCategory()),
                nz(game.getPlatform()),
                nz(game.getPublisher()),
                game.getReleaseDate() == null ? "-" : game.getReleaseDate(),
                nz(game.getSummary()));
    }

    /** 按名称(支持包含匹配)定位游戏,找不到返回 null */
    private Game findGame(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim();
        return gameMapper.selectGames().stream()
                .filter(g -> n.equals(g.getName())
                        || (g.getName() != null && (n.contains(g.getName()) || g.getName().contains(n))))
                .findFirst()
                .orElse(null);
    }

    private String nz(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}
