package com.tutor.learning;

import com.tutor.common.constant.TutorKeys;

import com.tutor.conversation.entity.MessageEntity;
import com.tutor.conversation.mapper.MessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学习进度状态机：Redis 持久化（key: tutor:session:{id}:state，TTL 7 天）。
 * 容错降级：Redis 不可用时退化为进程内 Map，保证流水线不中断（对应分层容错-工具层）。
 * 断点续跑语义：状态 + 已完成环节记录都在会话状态里，重启后 GET /state 仍可恢复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionStateService {

    private static final String KEY_PREFIX = TutorKeys.SESSION;
    private static final String KEY_SUFFIX = ":state";
    private static final String SLOTS_SUFFIX = ":slots";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final MessageMapper messageMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());

    /** Redis 故障时的进程内降级存储 */
    private final Map<String, String> fallbackStore = new ConcurrentHashMap<>();

    public void set(String sessionId, LearningState state) {
        try {
            redis.opsForValue().set(KEY_PREFIX + sessionId + KEY_SUFFIX, state.name(), TTL);
        } catch (Exception e) {
            log.warn("Redis 不可用，状态机降级为进程内存储: {}", e.getMessage());
            fallbackStore.put(sessionId, state.name());
        }
    }

    public LearningState get(String sessionId) {
        String value;
        try {
            value = redis.opsForValue().get(KEY_PREFIX + sessionId + KEY_SUFFIX);
        } catch (Exception e) {
            log.warn("Redis 不可用，从进程内读取状态: {}", e.getMessage());
            value = fallbackStore.get(sessionId);
        }
        if (value != null) {
            return LearningState.valueOf(value);
        }
        return deriveFromMessages(sessionId);
    }

    public LearningState getOrDefault(String sessionId, LearningState defaults) {
        LearningState s = get(sessionId);
        return s != null ? s : defaults;
    }

    /**
     * 状态未显式记录时，从已落库的消息类型推导（与 Mock deriveState 同款规则）。
     * 纯交易/闲聊会话没有教学信号 → 返回 null（前端右侧面板显示占位符，
     * 体现「旁路交易不影响教学状态机」的架构设计）。
     */
    private LearningState deriveFromMessages(String sessionId) {
        List<MessageEntity> messages = messageMapper.selectList(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, sessionId)
                .orderByAsc(MessageEntity::getTs));
        List<String> types = messages.stream().map(MessageEntity::getType).toList();
        if (types.contains("grade") || types.contains("report")) return LearningState.EVALUATED;
        if (types.contains("exercise")) return LearningState.PRACTICING;
        if (types.contains("plan")) return LearningState.PLANNED;
        if (types.contains("diagnosis")) return LearningState.DIAGNOSED;
        return null;
    }

    public List<String> pipeline() {
        return Arrays.asList(LearningState.PIPELINE);
    }

    /**
     * 会话历史槽位持久化（对齐 diet-agent 的 SessionState.slots 落库语义）：
     * 槽位跨轮合并的历史来源，Redis JSON 存储，TTL 对齐会话状态 7 天。
     */
    public void saveSlots(String sessionId, com.tutor.router.Slots slots) {
        if (sessionId == null || sessionId.isBlank() || slots == null) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + sessionId + SLOTS_SUFFIX,
                    objectMapper.writeValueAsString(slots), TTL);
        } catch (Exception e) {
            log.warn("历史槽位写入失败（跨轮槽位合并降级为单轮）: {}", e.getMessage());
        }
    }

    /** 读取会话历史槽位（无记录/Redis 故障返回 null，调用方按无历史处理） */
    public com.tutor.router.Slots loadSlots(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + sessionId + SLOTS_SUFFIX);
            return json == null ? null : objectMapper.readValue(json, com.tutor.router.Slots.class);
        } catch (Exception e) {
            log.warn("历史槽位读取失败: {}", e.getMessage());
            return null;
        }
    }
}
