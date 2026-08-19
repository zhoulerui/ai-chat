package com.example.aichat.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.aichat.tool.GameTools;

/**
 * Agent 工具注册:把 @Tool 注解的方法收集为 ToolCallbackProvider,
 * ChatController 每次流式问答时挂到 ToolCallingChatOptions 上。
 */
@Configuration
public class ToolConfig {

    @Bean
    public ToolCallbackProvider gameToolsProvider(GameTools gameTools) {
        return MethodToolCallbackProvider.builder().toolObjects(gameTools).build();
    }
}
