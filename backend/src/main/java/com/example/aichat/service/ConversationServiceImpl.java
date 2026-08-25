package com.example.aichat.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.aichat.dto.ConversationDto;
import com.example.aichat.entity.ChatMessage;
import com.example.aichat.entity.Conversation;
import com.example.aichat.mapper.ChatMessageMapper;
import com.example.aichat.mapper.ConversationMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private static final String DEFAULT_TITLE = "新对话";

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;

    @Override
    public List<ConversationDto> listConversations() {
        return conversationMapper.selectAll();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createConversation(String title, Long gameId) {
        Conversation c = new Conversation();
        c.setTitle(title == null || title.isBlank() ? DEFAULT_TITLE : title);
        c.setGameId(gameId);
        conversationMapper.insert(c);
        log.info("新建会话 #{} title=[{}] gameId={}", c.getId(), c.getTitle(), gameId);
        return c.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renameConversation(Long id, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        conversationMapper.updateTitle(id, title.trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long id) {
        conversationMapper.deleteMessages(id);
        conversationMapper.delete(id);
        log.info("删除会话 #{}", id);
    }

    @Override
    public List<ChatMessage> listMessages(Long conversationId) {
        return chatMessageMapper.selectByConversation(conversationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendExchange(Long conversationId, String question, String answer, String referencesJson) {
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            log.warn("会话不存在,跳过落库: #{}", conversationId);
            return;
        }
        // 首轮提问自动生成标题
        if (DEFAULT_TITLE.equals(conv.getTitle())) {
            String t = question == null ? "" : question.trim().replaceAll("\\s+", " ");
            if (t.length() > 20) {
                t = t.substring(0, 20);
            }
            if (!t.isEmpty()) {
                conversationMapper.updateTitle(conversationId, t);
            }
        }
        insertMessage(conversationId, "user", question == null ? "" : question, null);
        insertMessage(conversationId, "assistant", answer == null ? "" : answer, referencesJson);
        conversationMapper.touch(conversationId);
    }

    private void insertMessage(Long conversationId, String role, String content, String referencesJson) {
        ChatMessage m = new ChatMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setReferencesJson(referencesJson);
        chatMessageMapper.insert(m);
    }
}
