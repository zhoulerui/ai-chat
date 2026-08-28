package com.example.aichat.config;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 轻量熔断器:保护 DeepSeek 上游调用。
 * 状态机:CLOSED(正常)→ OPEN(连续失败达阈值,短路)→ HALF_OPEN(等待窗口后放行试探)→ 成功则恢复 CLOSED。
 *
 * 配置:ai-chat.circuit-breaker.enabled / failure-threshold(连续失败次数)/ open-duration-ms(短路时长)
 * 触发条件由调用方判定(401/402/429/5xx 等视为失败)。
 */
@Component
public class LlmCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile State state = State.CLOSED;
    private volatile long openedAt = 0;

    @Value("${ai-chat.circuit-breaker.enabled:true}")
    private boolean enabled;

    @Value("${ai-chat.circuit-breaker.failure-threshold:5}")
    private int failureThreshold;

    @Value("${ai-chat.circuit-breaker.open-duration-ms:30000}")
    private long openDurationMs;

    /** 是否处于短路状态:是则调用方应快速失败,不再请求上游 */
    public boolean isOpen() {
        if (!enabled) {
            return false;
        }
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt > openDurationMs) {
                state = State.HALF_OPEN;   // 放行一个试探请求
                return false;
            }
            return true;
        }
        return false;
    }

    /** 上游调用成功:清零失败计数并恢复 CLOSED */
    public void onSuccess() {
        if (!enabled) {
            return;
        }
        consecutiveFailures.set(0);
        state = State.CLOSED;
    }

    /** 上游调用失败(401/402/429/5xx 等):计数,达阈值则短路 */
    public void onFailure() {
        if (!enabled) {
            return;
        }
        if (state == State.HALF_OPEN || consecutiveFailures.incrementAndGet() >= failureThreshold) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
        }
    }

    public State getState() {
        return state;
    }
}
