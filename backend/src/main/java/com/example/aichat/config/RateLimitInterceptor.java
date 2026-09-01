package com.example.aichat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 公网防刷限流拦截器:按客户端 IP 限流,超限返回 HTTP 429。
 * 具体算法由 RateLimiter 实现决定:
 *  - ai-chat.ratelimit.mode=memory(默认):内存固定窗口
 *  - ai-chat.ratelimit.mode=redis:Redis 滑动窗口(多实例共享,见 RedisSlidingWindowRateLimiter)
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    @Value("${ai-chat.ratelimit.enabled:true}")
    private boolean enabled;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled) {
            return true;
        }
        if (!rateLimiter.tryAcquire(clientIp(request))) {
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
