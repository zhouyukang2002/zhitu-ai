package com.tutor.knowledge;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.tutor.llm.LlmGateway;
import com.tutor.prompt.PromptStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查询改写（Query Rewrite）：RAG 检索的前置环节。
 * 多轮对话里学生的提问常带指代（"它怎么解？""那这个公式呢"），
 * 直接拿原句做向量/BM25 检索召回率很差——先用轻量 LLM 结合最近对话
 * 把问题改写成独立完整的检索查询，再进混合检索。
 * 容错：LLM 不可用/改写失败 → 原样返回（检索照常，只是召回质量退回改写前）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRewriter {

    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final com.tutor.conversation.MessageService messageService;
    private final com.tutor.config.AppProperties props;

    /**
     * 改写检索查询。返回改写后的查询；未触发/失败时返回原消息。
     */
    public String rewrite(String sessionId, String message) {
        if (!gateway.available() || StrUtil.isBlank(message)) {
            return message;
        }
        try {
            List<String> history = messageService.recentTexts(sessionId, 6);
            if (history.isEmpty()) {
                return message; // 会话首轮无上下文可消解
            }
            String historyText = String.join("\n", history);
            String system = promptStore.render(PromptStore.REWRITE, java.util.Map.of(
                    "history", historyText,
                    "message", message
            ));
            JsonNode node = gateway.callJson(
                    system,
                    "学生最新消息：" + message,
                    props.getAi().getRouteModel(), com.tutor.llm.LlmConstants.TEMP_DETERMINISTIC, null);
            String query = node.path("query").asText("").trim();
            if (query.isEmpty() || query.length() > 200) {
                return message;
            }
            if (!query.equals(message)) {
                // 结构化改写日志（query-rewrite 前缀便于评测脚本统计改写率与人工核对改写质量）
                log.info("[query-rewrite] session={} before=\"{}\" after=\"{}\"",
                        sessionId, message.replace("\"", "'"), query.replace("\"", "'"));
            }
            return query;
        } catch (Exception e) {
            log.warn("查询改写失败，使用原始消息检索: {}", e.getMessage());
            return message;
        }
    }
}
