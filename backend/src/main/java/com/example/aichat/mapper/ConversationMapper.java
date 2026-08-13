package com.example.aichat.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.example.aichat.dto.ConversationDto;
import com.example.aichat.entity.Conversation;

@Mapper
public interface ConversationMapper {

    List<ConversationDto> selectAll();

    Conversation selectById(@Param("id") Long id);

    int insert(Conversation conversation);

    int updateTitle(@Param("id") Long id, @Param("title") String title);

    int touch(@Param("id") Long id);

    int delete(@Param("id") Long id);

    int deleteMessages(@Param("conversationId") Long conversationId);
}
