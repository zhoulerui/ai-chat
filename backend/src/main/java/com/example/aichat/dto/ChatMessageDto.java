package com.example.aichat.dto;

/**
 * 单条对话消息。
 *
 * @param role      user / assistant / system
 * @param content   消息内容
 * @param reasoning 可选:assistant 消息的思考过程(DeepSeek thinking 模型)。
 *                  跨轮对话时需回传(官方:有过工具调用的轮次必须回传;无工具调用时传了被忽略)
 */
public record ChatMessageDto(String role, String content, String reasoning) {
}
