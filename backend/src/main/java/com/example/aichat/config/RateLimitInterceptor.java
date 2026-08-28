package com.example.aichat.config;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 轻量限流(公网防刷):固定窗口计数,按客户端 IP 限流。
 * 超限返回 HTTP 429。单机内存实现,无外部依赖。
 *
 * 配置:ai-chat.ratelimit.enabled / per-ip-per-minute(默认 20 次/分钟/IP)
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** ip -> [窗口起始毫秒, 窗口内计数] */
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    @Value("${ai-chat.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${ai-chat.ratelimit.per-ip-per-minute:20}")
    private int perIpPerMinute;

    private static final long WINDOW_MS = 60_000L;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled) {
            return true;
        }
        String ip = clientIp(request);
        long now = System.currentTimeMillis();
        long[] entry = buckets.compute(ip, (k, v) -> {
            if (v == null || now - v[0] > WINDOW_MS) {
                return new long[]{now, 1};
            }
            v[1]++;
            return v;
        });
        if (entry[1] > perIpPerMinute) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求过于频繁,请稍后再试\"}");
            return false;
        }
        return true;
    }

    /** 取客户端 IP:优先 X-Forwarded-For(公网 nginx/代理 场景),取第一个;否则 remoteAddr */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
