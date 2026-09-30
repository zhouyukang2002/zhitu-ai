package com.tutor.llm;

import com.tutor.common.constant.TutorKeys;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 短期记忆的 Redis 仓储（Spring AI GA 的 ChatMemoryRepository SPI）。
 * 存储：tutor:memory:{conversationId}，List 结构，TTL 7 天；
 * 滑动窗口裁剪由 MessageWindowChatMemory(maxMessages) 负责，仓储只管存取。
 * 对应话术：Redis List 高性能读写 + TTL 自动回收不活跃会话资源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY_PREFIX = TutorKeys.MEMORY;
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    @Override
    public List<String> findConversationIds() {
        List<String> ids = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                ids.add(cursor.next().substring(KEY_PREFIX.length()));
            }
        } catch (Exception e) {
            log.warn("短期记忆会话枚举失败: {}", e.getMessage());
        }
        return ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        try {
            List<String> raw = redis.opsForList().range(KEY_PREFIX + conversationId, 0, -1);
            if (raw == null) {
                return List.of();
            }
            List<Message> messages = new ArrayList<>();
            for (String json : raw) {
                try {
                    messages.add(toMessage(MAPPER.readTree(json)));
                } catch (Exception e) {
                    log.warn("记忆消息解析失败，跳过: {}", e.getMessage());
                }
            }
            return messages;
        } catch (Exception e) {
            log.warn("Redis 不可用，短期记忆降级为空: {}", e.getMessage());
            return List.of();
        }
    }

    /** GA 语义：以传入列表全量覆盖该会话的存储 */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            String key = KEY_PREFIX + conversationId;
            redis.delete(key);
            if (!messages.isEmpty()) {
                List<String> jsonList = CollStreamUtil.toList(messages, this::toJson);
                redis.opsForList().rightPushAll(key, jsonList);
                redis.expire(key, TTL);
            }
        } catch (Exception e) {
            log.warn("短期记忆写入失败（记忆功能降级，不影响主流程）: {}", e.getMessage());
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        try {
            redis.delete(KEY_PREFIX + conversationId);
        } catch (Exception e) {
            log.warn("短期记忆清理失败: {}", e.getMessage());
        }
    }

    private String toJson(Message message) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "type", message.getMessageType().name(),
                    "text", StrUtil.nullToEmpty(message.getText())
            ));
        } catch (Exception e) {
            return "{\"type\":\"ASSISTANT\",\"text\":\"\"}";
        }
    }

    private Message toMessage(JsonNode node) {
        MessageType type = MessageType.valueOf(node.get("type").asText());
        String text = node.get("text").asText();
        return switch (type) {
            case SYSTEM -> new SystemMessage(text);
            case USER -> new UserMessage(text);
            case ASSISTANT -> new AssistantMessage(text);
            case TOOL -> new AssistantMessage(text);
        };
    }
}
