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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatModel chatModel;
    private final RagService ragService;
    private final ConversationService conversationService;
    private final ToolCallbackProvider toolCallbackProvider;
    private final com.example.aichat.config.LlmCircuitBreaker circuitBreaker;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 模型档位:ai-chat.models.*(yml 配置),前端 modelId 映射到实际 DeepSeek 模型名 */
    @Value("${ai-chat.models.fast:deepseek-chat}")
    private String fastModel;
    @Value("${ai-chat.models.deep:deepseek-v4-flash}")
    private String deepModel;
    @Value("${ai-chat.models.pro:deepseek-v4-pro}")
    private String proModel;

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

        // 熔断:上游(DeepSeek)连续失败短路期间,快速失败,不再请求
        if (circuitBreaker.isOpen()) {
            sendEvent(emitter, "error", "模型服务暂时不可用,请稍后再试");
            emitter.complete();
            return emitter;
        }

        List<Message> messages = new ArrayList<>();
        String question = lastUserMessage(request.messages());
        StringBuilder answer = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<Map<String, Object>> refs = new ArrayList<>();

        // 诊断:记录本次请求的消息构成(角色 + 是否带思考 + content 长度),排查 400 用
        log.info("stream 请求: modelId={} gameId={} convId={} messages={}",
                request.modelId(), request.gameId(), request.conversationId(),
                request.messages().stream()
                        .map(m -> m.role()
                                + (m.reasoning() != null && !m.reasoning().isBlank() ? "[R]" : "")
                                + (m.content() != null ? ":" + m.content().length() : ""))
                        .toList());

        // 引导模型用中文思考:thinking 档(reasoning_content)默认用英文内心独白,
        // 显式指令可让其改用中文(实测 DeepSeek 会遵守;fast 档无思考,无副作用)
        messages.add(new SystemMessage("请用中文进行思考与推理,最终回答使用中文。"));

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
                .filter(java.util.Objects::nonNull)   // 防御:跳过空占位 assistant 消息
                .forEach(messages::add);

        Flux<ChatResponse> responses = chatModel.stream(new Prompt(messages, buildToolOptions(resolveModel(request.modelId()))));

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
                // 参考来源 + 思考过程统一存 references_json:
                // 新格式 {"references":[...], "reasoning":"..."}(旧版是裸数组,前端兼容解析)
                String rsn = reasoning.toString();
                Map<String, Object> refsPayload = Map.of(
                        "references", refs,
                        "reasoning", (rsn == null || rsn.isBlank()) ? "" : rsn);
                String refsJson = MAPPER.writeValueAsString(refsPayload);
                conversationService.appendExchange(
                        request.conversationId(), question, answer.toString(), refsJson);
                log.info("会话 #{} 已落库:问题[{}] 回答 {} 字符,思考 {} 字符",
                        request.conversationId(), question, answer.length(), rsn.length());
            } catch (Exception e) {
                log.warn("会话消息落库失败: conversationId={} err={}", request.conversationId(), e.getMessage());
            }
        };

        Disposable disposable = responses.subscribe(
                resp -> {
                    if (resp.getResult() == null || resp.getResult().getOutput() == null) {
                        return;
                    }
                    var out = resp.getResult().getOutput();
                    // 思考过程(仅 DeepSeek thinking 模型有):单独事件,前端灰色折叠区渲染
                    if (out instanceof DeepSeekAssistantMessage dm) {
                        String r = dm.getReasoningContent();
                        if (r != null && !r.isBlank()) {
                            reasoning.append(r);
                            sendEvent(emitter, "reasoning", r);
                        }
                    }
                    String text = out.getText();
                    if (text != null && !text.isBlank()) {
                        answer.append(text);
                        safeSend(emitter, text);
                    }
                },
                error -> {
                    // 优先展示 API 返回的具体错误(400/401/429 等带响应体,含原因)
                    String msg = error.getMessage();
                    if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException we) {
                        msg = we.getStatusCode() + " " + we.getResponseBodyAsString();
                        log.error("LLM 调用失败(status={}): {}", we.getStatusCode(), we.getResponseBodyAsString());
                        // 上游明确失败(鉴权/余额/限流/5xx)计入熔断
                        int code = we.getStatusCode().value();
                        if (code == 401 || code == 402 || code == 429 || code >= 500) {
                            circuitBreaker.onFailure();
                        } else {
                            circuitBreaker.onSuccess();   // 4xx 业务参数错误不熔断,视为正常
                        }
                    } else {
                        log.error("LLM 调用失败: {}", error.getMessage());
                    }
                    sendEvent(emitter, "error", msg);
                    persist.run();
                    emitter.complete();
                },
                () -> {
                    circuitBreaker.onSuccess();   // 完整流式输出 = 上游成功,熔断复位
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
     * 挂载 Agent 工具(Function Calling)+ 模型档位:
     * 模型按需调用 GameTools(@Tool 方法),Spring AI 在流式过程中自动执行
     * 工具并继续生成最终回答(内部工具调用循环),前端无感知、仍是普通文本流。
     */
    private ToolCallingChatOptions buildToolOptions(String model) {
        return ToolCallingChatOptions.builder()
                .model(model)
                .toolCallbacks(toolCallbackProvider.getToolCallbacks())
                .build();
    }

    /** modelId → 实际模型名;未知档位回退快速档 */
    private String resolveModel(String modelId) {
        return switch (modelId == null ? "fast" : modelId) {
            case "deep" -> deepModel;
            case "pro" -> proModel;
            default -> fastModel;
        };
    }

    private Message toSpringMessage(ChatMessageDto dto) {
        // 防御:跳过 content 为空的 assistant 占位消息(前端已过滤,双保险)
        if ("assistant".equals(dto.role())
                && (dto.content() == null || dto.content().isBlank())) {
            return null;
        }
        return switch (dto.role()) {
            case "system" -> new SystemMessage(dto.content());
            case "assistant" -> {
                // 跨轮回传思考过程:thinking 模型做过工具调用的轮次必须回传
                // reasoning_content(无工具调用时传了会被 API 忽略,无条件回传最稳)
                if (dto.reasoning() != null && !dto.reasoning().isBlank()) {
                    yield assistantWithReasoning(dto.content(), dto.reasoning());
                }
                yield new AssistantMessage(dto.content());
            }
            default -> new UserMessage(dto.content());
        };
    }

    /**
     * 构造带 reasoning_content 的 assistant 消息(prefix=false,普通历史消息)。
     * 不能用 prefixAssistantMessage(它把 prefix 置 true,DeepSeek 拒绝非末尾消息带 prefix)。
     */
    private static Message assistantWithReasoning(String content, String reasoning) {
        try {
            var ctor = DeepSeekAssistantMessage.class.getDeclaredConstructor(
                    String.class, String.class, Boolean.class, Map.class, List.class, List.class);
            ctor.setAccessible(true);
            return ctor.newInstance(content, reasoning, Boolean.FALSE, null, null, null);
        } catch (Exception e) {
            log.warn("构造 DeepSeekAssistantMessage 失败,退回普通消息: {}", e.getMessage());
            return new AssistantMessage(content);
        }
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
