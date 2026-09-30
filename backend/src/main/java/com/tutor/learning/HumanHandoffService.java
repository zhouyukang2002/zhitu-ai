package com.tutor.learning;

import com.tutor.common.constant.TutorKeys;

import com.tutor.agent.TraceStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转人工（Human Handoff）：按会话统计连续降级/失败轮次，
 * 连续达到阈值（默认 3 轮）说明 AI 服务质量已不可接受，
 * 下发 system 卡片告知学生已转人工，避免用户在降级体验里无限打转。
 * 计数语义：成功轮次清零重新累计；触发转人工后清零（不重复刷屏，再次连续异常才再次触发）。
 * 计数存 Redis（TTL 7 天对齐会话状态），Redis 故障退化为进程内 Map。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanHandoffService {

    private static final String KEY_PREFIX = TutorKeys.SESSION;
    private static final String KEY_SUFFIX = ":degraded_streak";
    private static final Duration TTL = Duration.ofDays(7);
    /** 连续降级/失败多少轮触发转人工 */
    private static final int THRESHOLD = 3;

    private final StringRedisTemplate redis;

    /** Redis 故障时的进程内降级计数 */
    private final Map<String, Long> fallbackCounters = new ConcurrentHashMap<>();

    /**
     * 记录一轮执行结果。返回 true 表示本轮达到阈值、应当下发转人工卡片。
     *
     * @param status 轮次状态：success / degraded / failed（TraceStep 语义）
     */
    public boolean recordRound(String sessionId, String status) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (!TraceStep.FAILED.equals(status) && !TraceStep.DEGRADED.equals(status)) {
            reset(sessionId);
            return false;
        }
        long streak;
        try {
            String key = KEY_PREFIX + sessionId + KEY_SUFFIX;
            streak = redis.opsForValue().increment(key);
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("Redis 不可用，转人工计数退化为进程内: {}", e.getMessage());
            streak = fallbackCounters.merge(sessionId, 1L, Long::sum);
        }
        if (streak >= THRESHOLD) {
            reset(sessionId);
            log.warn("会话连续 {} 轮降级/失败，触发转人工: {}", streak, sessionId);
            return true;
        }
        return false;
    }

    private void reset(String sessionId) {
        try {
            redis.delete(KEY_PREFIX + sessionId + KEY_SUFFIX);
        } catch (Exception e) {
            fallbackCounters.remove(sessionId);
        }
    }
}
