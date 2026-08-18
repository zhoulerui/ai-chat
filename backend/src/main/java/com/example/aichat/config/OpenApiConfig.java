package com.example.aichat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;

/**
 * OpenAPI(Swagger)文档配置。
 * 访问入口:
 *   - UI:   http://host:port/swagger-ui.html
 *   - JSON: http://host:port/v3/api-docs
 * 生产环境如需关闭:application.yml 里 springdoc.api-docs.enabled=false 并重启。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI oracleGamesOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("神谕百科 API")
                .description("游戏知识问答系统:SSE 流式问答 / 多会话管理 / 知识库(RAG)管理 / 网址入库")
                .version("v1.0.0")
                .contact(new Contact().name("神谕百科 Oracle of Games")));
    }
}
