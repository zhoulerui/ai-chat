package com.example.aichat.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 滑动窗口限流(切换模式:ai-chat.ratelimit.mode=redis)。
 *
 * 原理:每 IP 一个 ZSET,member=请求唯一标识,score=请求毫秒时间戳;
 * 每次请求先剔除窗口外(score < now-window)的旧请求,再统计窗口内数量:
 *  - 未达上限:ZADD 当前请求,EXPIRE 兜底过期,返回 1(放行)
 *  - 已达上限:返回 0(拒绝)
 * Lua 脚本整体原子执行,多实例部署时计数共享(分布式限流)。
 *
 * 降级:Redis 不可用时 tryAcquire 捕获异常返回 true(放行),服务不因限流组件不可用而挂。
 * 注意:滑动窗口解决固定窗口的"边界突刺",严格满足"任意连续 60 秒内 ≤ N 次"。
 */
@Component
@ConditionalOnProperty(name = "ai-chat.ratelimit.mode", havingValue = "redis")
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);

    /** 滑动窗口 Lua:KEYS[1]=key;ARGV[1]=now,ARGV[2]=windowMs,ARGV[3]=limit,ARGV[4]=member */
    private static final String LUA =
            "local key = KEYS[1] " +
            "local now = tonumber(ARGV[1]) " +
            "local window = tonumber(ARGV[2]) " +
            "local limit = tonumber(ARGV[3]) " +
            "local member = ARGV[4] " +
            "redis.call('ZREMRANGEBYSCORE', key, 0, now - window) " +
            "local count = redis.call('ZCARD', key) " +
            "if count < limit then " +
            "  redis.call('ZADD', key, now, member) " +
            "  redis.call('EXPIRE', key, window) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);
    private final AtomicLong seq = new AtomicLong();

    @Value("${ai-chat.ratelimit.per-ip-per-minute:20}")
    private int perIpPerMinute;

    private static final long WINDOW_MS = 60_000L;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String ip) {
        try {
            long now = System.currentTimeMillis();
            String member = now + ":" + seq.incrementAndGet();
            Long result = redisTemplate.execute(script,
                    List.of("ratelimit:" + ip),
                    String.valueOf(now),
                    String.valueOf(WINDOW_MS),
                    String.valueOf(perIpPerMinute),
                    member);
            return result == null || result == 1L;
        } catch (Exception e) {
            // Redis 不可用:降级放行,保证服务可用(限流功能暂时失效)
            log.warn("Redis 限流不可用,降级放行: {}", e.getMessage());
            return true;
        }
    }
}
