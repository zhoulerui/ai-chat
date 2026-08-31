package com.example.aichat.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.aichat.service.MetaProbeService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/**
 * 多数据源验证接口:
 * GET /api/meta/datasource → 返回 primary 与 dev 两个数据源实际连接的库名。
 *  - 生产:{"primary":"ai_chat","dev":"ai_chat_dev"}
 *  - 本地(local):primary=h2 正常,dev 源未初始化则返回"不可用"(证明 @DS 确实尝试切换)
 */
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetaController {

    private final MetaProbeService probeService;

    @Operation(summary = "多数据源状态", description = "返回 primary 与 dev 数据源实际连接的库名,验证 @DS 路由")
    @GetMapping("/datasource")
    public Map<String, Object> datasource() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("primary", safe(probeService::primaryDb));
        result.put("dev", safe(probeService::devDb));
        return result;
    }

    private String safe(Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            String msg = e.getMessage();
            return "不可用: " + (msg == null ? e.getClass().getSimpleName()
                    : msg.substring(0, Math.min(60, msg.length())));
        }
    }
}
