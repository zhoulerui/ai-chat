package com.example.aichat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地嵌入模型(ONNX + onnxruntime,纯 Java,无需 Ollama)。
 * 模型文件:bge-small-zh-v1.5(model.onnx + tokenizer.json),
 * 路径通过 ai-chat.onnx.* 配置(见 application.yml / application-local.yml)。
 */
@Configuration
public class OnnxEmbeddingConfig {

    @Bean
    public LocalBgeEmbeddingModel localBgeEmbeddingModel(
            @Value("${ai-chat.onnx.model-path}") String modelPath,
            @Value("${ai-chat.onnx.tokenizer-path}") String tokenizerPath) throws Exception {
        return new LocalBgeEmbeddingModel(modelPath, tokenizerPath);
    }
}
