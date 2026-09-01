package com.example.aichat.config;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存固定窗口限流(默认实现,零依赖):
 * 每 IP 60 秒窗口内最多 N 次,超限拒绝。O(1) 内存,单机场景够用。
 */
@Component
@ConditionalOnProperty(name = "ai-chat.ratelimit.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    /** ip -> [窗口起始毫秒, 窗口内计数] */
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    @Value("${ai-chat.ratelimit.per-ip-per-minute:20}")
    private int perIpPerMinute;

    private static final long WINDOW_MS = 60_000L;

    @Override
    public boolean tryAcquire(String ip) {
        long now = System.currentTimeMillis();
        long[] entry = buckets.compute(ip, (k, v) -> {
            if (v == null || now - v[0] > WINDOW_MS) {
                return new long[]{now, 1};
            }
            v[1]++;
            return v;
        });
        return entry[1] <= perIpPerMinute;
    }
}
