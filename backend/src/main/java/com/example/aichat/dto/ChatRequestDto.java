package com.example.aichat.dto;

import java.util.List;

public record ChatRequestDto(Long gameId, Long conversationId, List<ChatMessageDto> messages) {
}
