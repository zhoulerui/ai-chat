package com.example.aichat.dto;

import java.util.List;

/**
 * 流式问答请求。
 *
 * @param gameId         可选:带则检索该游戏知识库(RAG)
 * @param conversationId 可选:带则回答完成后自动落库
 * @param modelId        可选:模型档位,fast=快速(deepseek-chat) / deep=深度思考(deepseek-v4-flash),默认 fast
 * @param messages       对话历史(最后一条为本次问题)
 */
public record ChatRequestDto(Long gameId, Long conversationId, String modelId, List<ChatMessageDto> messages) {
}
