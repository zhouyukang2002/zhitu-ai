package com.tutor.llm;

import com.tutor.common.constant.TutorKeys;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 超限摘要（Context Compaction）：短期记忆滑动窗口（20 条）只做硬裁剪，
 * 被挤出的最旧消息直接丢失——长会话里"我们刚才聊到哪了"就断了。
 * 本服务在记忆逼近窗口上限时，把窗口外/即将被裁剪的旧消息用 LLM 压缩成
 * 一段摘要存 Redis（tutor:memory:summary:{id}，TTL 7 天），并裁剪存储层，
 * 摘要注入后续 system prompt，实现"丢细节不丢语境"。
 * 容错：任一环节失败返回空摘要，主流程照常（退回纯滑动窗口语义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryCompactionService {

    private static final String SUMMARY_PREFIX = TutorKeys.MEMORY + "summary:";
    private static final Duration TTL = Duration.ofDays(7);
    /** 与 SpringAIConfig 的 MessageWindowChatMemory.maxMessages 对齐 */
    private static final int WINDOW = 20;
    /** 触发压缩后保留的最近消息条数 */
    private static final int KEEP = 10;

    private final ChatMemoryRepository memoryRepository;
    private final StringRedisTemplate redis;
    private final LlmGateway gateway;
    private final com.tutor.prompt.PromptStore promptStore;
    private final com.tutor.config.AppProperties props;

    /**
     * 记忆超过保留水位时压缩并返回最新摘要（未触发则返回已有摘要，可能为空）。
     */
    public String compactIfNeeded(String conversationId) {
        String existing = loadSummary(conversationId);
        if (StrUtil.isBlank(conversationId) || !gateway.available()) {
            return existing;
        }
        try {
            List<Message> messages = memoryRepository.findByConversationId(conversationId);
            if (messages.size() < WINDOW) {
                return existing;
            }
            String summary = summarize(conversationId, existing, messages);
            // 摘要成功后裁剪存储，只保留最近 KEEP 条（否则窗口每轮都在重复压缩旧消息）
            memoryRepository.saveAll(conversationId, messages.subList(messages.size() - KEEP, messages.size()));
            redis.opsForValue().set(SUMMARY_PREFIX + conversationId, summary, TTL);
            log.info("短期记忆已压缩: {} 条 -> {} 条 + 摘要 {} 字", messages.size(), KEEP, summary.length());
            return summary;
        } catch (Exception e) {
            log.warn("记忆压缩失败（退回纯滑动窗口）: {}", e.getMessage());
            return existing;
        }
    }

    private String summarize(String conversationId, String existing, List<Message> messages) {
        List<Message> older = messages.subList(0, messages.size() - KEEP);
        StringBuilder history = new StringBuilder();
        if (StrUtil.isNotBlank(existing)) {
            history.append("【更早的摘要】").append(existing).append("\n\n");
        }
        for (Message m : older) {
            String role = switch (m.getMessageType()) {
                case USER -> "学生";
                case ASSISTANT -> "助教";
                default -> "系统";
            };
            history.append(role).append("：").append(StrUtil.nullToEmpty(m.getText())).append("\n");
        }
        String summary = gateway.callText(promptStore.get(com.tutor.prompt.PromptStore.SUMMARIZE),
                history.toString(), props.getAi().getRouteModel(), 0.3, null);
        summary = summary == null ? "" : summary.trim();
        return summary.isEmpty() ? existing : summary;
    }

    /** 仅读取已有摘要（不触发压缩），供讲解等无记忆链路注入上下文 */
    public String loadSummary(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return "";
        }
        try {
            return StrUtil.nullToEmpty(redis.opsForValue().get(SUMMARY_PREFIX + conversationId));
        } catch (Exception e) {
            return "";
        }
    }
}
