package com.example.aichat.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.example.aichat.entity.ChatMessage;

@Mapper
public interface ChatMessageMapper {

    List<ChatMessage> selectByConversation(@Param("conversationId") Long conversationId);

    int insert(ChatMessage message);
}
