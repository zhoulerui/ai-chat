package com.example.aichat.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.aichat.dto.ChatMessageDto;
import com.example.aichat.dto.ChatRequestDto;
import com.example.aichat.dto.ConversationDto;
import com.example.aichat.entity.ChatMessage;
import com.example.aichat.rag.ChunkHit;
import com.example.aichat.rag.RagService;
import com.example.aichat.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * 流式聊天 + 多会话管理。
 * 前端把完整对话历史 POST 过来,后端逐 token 通过 SSE 推送;
 * 可选 RAG:带 gameId 时检索游戏知识库拼进 SystemMessage;
 * 带 conversationId 时,回答完成/停止后自动持久化本轮问答。
 */
@Tag(name = "智能问答", description = "SSE 流式问答 + 多会话管理")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatModel chatModel;
    private final RagService ragService;
    private final ConversationService conversationService;
    private final ToolCallbackProvider toolCallbackProvider;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ChatController(ChatModel chatModel, RagService ragService,
                          ConversationService conversationService,
                          ToolCallbackProvider toolCallbackProvider) {
        this.chatModel = chatModel;
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    // ---------- 会话管理 ----------

    @Operation(summary = "会话列表", description = "含消息数,按最近更新倒序")
    @GetMapping("/conversations")
    public List<ConversationDto> conversations() {
        return conversationService.listConversations();
    }

    @Operation(summary = "新建会话", description = "body 可选: {title?, gameId?}")
    @PostMapping("/conversations")
    public Map<String, Object> createConversation(@RequestBody(required = false) Map<String, Object> body) {
        String title = body == null ? null : (String) body.get("title");
        Long gameId = body == null || body.get("gameId") == null
                ? null : Long.valueOf(body.get("gameId").toString());
        Long id = conversationService.createConversation(title, gameId);
        return Map.of("id", id);
    }

    @Operation(summary = "历史消息", description = "含 references(参考来源 JSON)")
    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessage> messages(@PathVariable Long id) {
        return conversationService.listMessages(id);
    }

    @Operation(summary = "重命名会话", description = "body: {title}")
    @PatchMapping("/conversations/{id}")
    public Map<String, Object> renameConversation(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> body) {
        conversationService.renameConversation(id, (String) body.get("title"));
        return Map.of("ok", true);
    }

    @Operation(summary = "删除会话", description = "级联删除全部消息")
    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> deleteConversation(@PathVariable Long id) {
        conversationService.deleteConversation(id);
        return Map.of("ok", true);
    }

    // ---------- 流式问答 ----------

    @Operation(summary = "流式问答(SSE)", description = "body: {gameId?, conversationId?, messages:[{role,content}]};gameId 触发 RAG 检索并推送 references 事件;conversationId 使本轮问答自动落库")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequestDto request) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不超时,等待模型输出

        List<Message> messages = new ArrayList<>();
        String question = lastUserMessage(request.messages());
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> refs = new ArrayList<>();

        // RAG:带 gameId 时,以最后一条用户消息为问题,检索知识库拼进 SystemMessage,
        // 并把参考来源通过 references 事件推给前端(侧边栏展示)
        if (request.gameId() != null) {
            List<ChunkHit> hits = ragService.search(request.gameId(), question, 4);
            if (!hits.isEmpty()) {
                messages.add(new SystemMessage(ragService.buildPromptFromHits(hits)));
                log.info("RAG 问答:gameId={} 命中 {} 条参考,已拼入提示词", request.gameId(), hits.size());
                try {
                    List<Map<String, Object>> refList = hits.stream()
                            .map(h -> Map.<String, Object>of(
                                    "similarity", Math.round(h.similarity() * 1000) / 1000.0,
                                    "content", h.content(),
                                    "chunkId", h.id(),
                                    "articleId", h.articleId() == null ? null : h.articleId(),
                                    "articleTitle", h.articleTitle() == null ? null : h.articleTitle()))
                            .toList();
                    refs.addAll(refList);
                    sendEvent(emitter, "references", MAPPER.writeValueAsString(refList));
                } catch (Exception ignored) {
                    // 参考来源序列化失败不影响主流程
                }
            } else {
                log.info("RAG 问答:gameId={} 未命中参考,走通用回答", request.gameId());
            }
        }
        request.messages().stream()
                .map(this::toSpringMessage)
                .forEach(messages::add);

        Flux<String> tokens = chatModel.stream(new Prompt(messages, buildToolOptions()))
                // mapNotNull:流式最后一个 chunk 通常没有文本(只带 finish_reason/usage),
                // getText() 会返回 null,而 map() 不允许返回 null,必须用 mapNotNull 跳过
                .mapNotNull(response -> {
                    if (response.getResult() == null || response.getResult().getOutput() == null) {
                        return null;
                    }
                    String text = response.getResult().getOutput().getText();
                    return (text == null || text.isBlank()) ? null : text;
                });

        // 落库只执行一次(正常完成/异常/客户端断开任一触发)
        AtomicBoolean saved = new AtomicBoolean(false);
        Runnable persist = () -> {
            if (!saved.compareAndSet(false, true)) {
                return;
            }
            if (request.conversationId() == null) {
                return;
            }
            try {
                String refsJson = refs.isEmpty() ? null : MAPPER.writeValueAsString(refs);
                conversationService.appendExchange(
                        request.conversationId(), question, answer.toString(), refsJson);
                log.info("会话 #{} 已落库:问题[{}] 回答 {} 字符",
                        request.conversationId(), question, answer.length());
            } catch (Exception e) {
                log.warn("会话消息落库失败: conversationId={} err={}", request.conversationId(), e.getMessage());
            }
        };

        Disposable disposable = tokens.subscribe(
                token -> {
                    answer.append(token);
                    safeSend(emitter, token);
                },
                error -> {
                    sendEvent(emitter, "error", error.getMessage());
                    persist.run();
                    emitter.complete();
                },
                () -> {
                    persist.run();
                    emitter.complete();
                });

        // 客户端断开(停止生成/关页面/超时)时:取消订阅,并尝试落库已生成的内容
        emitter.onCompletion(() -> {
            persist.run();
            disposable.dispose();
        });
        emitter.onError(e -> {
            persist.run();
            disposable.dispose();
        });

        return emitter;
    }

    /**
     * 挂载 Agent 工具(Function Calling):
     * 模型按需调用 GameTools(@Tool 方法),Spring AI 1.0 在流式过程中自动执行
     * 工具并继续生成最终回答(内部工具调用循环),前端无感知、仍是普通文本流。
     */
    private ToolCallingChatOptions buildToolOptions() {
        return ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbackProvider.getToolCallbacks())
                .build();
    }

    private Message toSpringMessage(ChatMessageDto dto) {
        return switch (dto.role()) {
            case "system" -> new SystemMessage(dto.content());
            case "assistant" -> new AssistantMessage(dto.content());
            default -> new UserMessage(dto.content());
        };
    }

    /** 取最后一条用户消息作为检索问题 */
    private String lastUserMessage(List<ChatMessageDto> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessageDto d = msgs.get(i);
            if ("user".equals(d.role())) {
                return d.content();
            }
        }
        return "";
    }

    private void safeSend(SseEmitter emitter, String data) {
        sendEvent(emitter, "message", data);
    }

    /**
     * 关键:data 必须是"单行安全"的文本。
     * SSE 协议中 data: 行以换行结尾,若 token 本身含 \n 或行首空格,
     * 直接发送会破坏帧结构(换行丢失、缩进被吞)。
     * 因此统一用 JSON 序列化(换行/引号/空格全部转义),前端 JSON.parse 还原。
     */
    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            String json = MAPPER.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(name).data(json));
        } catch (Exception ignored) {
            // 客户端已断开,忽略即可
        }
    }
}
