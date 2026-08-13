package com.example.aichat.service;

import java.util.List;

import com.example.aichat.dto.ConversationDto;
import com.example.aichat.entity.ChatMessage;

/**
 * 多会话服务接口。
 * 实现:com.example.aichat.service.ConversationServiceImpl
 */
public interface ConversationService {

    List<ConversationDto> listConversations();

    /** 新建会话,返回 id */
    Long createConversation(String title, Long gameId);

    void renameConversation(Long id, String title);

    /** 删除会话及其全部消息 */
    void deleteConversation(Long id);

    List<ChatMessage> listMessages(Long conversationId);

    /**
     * 追加一轮问答(user + assistant)并落库;
     * 若会话标题仍为默认值,用首条提问自动生成标题。
     */
    void appendExchange(Long conversationId, String question, String answer, String referencesJson);
}
