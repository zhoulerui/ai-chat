package com.example.aichat.config;

/**
 * 限流器抽象:拦截器只依赖此接口,具体算法可切换。
 * 实现:InMemoryRateLimiter(内存固定窗口,默认)/ RedisSlidingWindowRateLimiter(Redis 滑动窗口)。
 * 切换:ai-chat.ratelimit.mode = memory | redis
 */
public interface RateLimiter {

    /**
     * 尝试获取一次配额。
     *
     * @param ip 客户端标识(IP)
     * @return true=放行;false=超限拒绝
     */
    boolean tryAcquire(String ip);
}
