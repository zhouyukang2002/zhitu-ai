package com.tutor.memory;

import cn.hutool.core.util.IdUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.tutor.knowledge.EmbeddingService;
import com.tutor.knowledge.KnowledgeIngestionService;
import com.tutor.llm.LlmGateway;
import com.tutor.llm.LlmUsageHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户跨会话长期语义记忆服务（基于 ES 8.x user_memory 索引）。
 * 异步提取学生自述薄弱点与偏好，并在新会话中基于 Dense Vector 语义召回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;
    private final LlmGateway gateway;

    /**
     * 跨会话语义记忆召回（带 userId 租户强隔离与相似度过滤）。
     */
    public List<UserMemoryItem> recallMemories(Long userId, String query, int topK) {
        if (userId == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<Float> queryVector = embeddingService.embed(query);

            SearchResponse<UserMemoryItem> response = esClient.search(s -> s
                    .index(KnowledgeIngestionService.MEMORY_INDEX)
                    .knn(knn -> knn
                            .field("embedding")
                            .queryVector(queryVector)
                            .k(topK)
                            .numCandidates(20)
                            .filter(f -> f.term(t -> t.field("userId").value(userId)))
                    )
                    .size(topK),
                    UserMemoryItem.class
            );

            if (response.hits() != null && response.hits().hits() != null) {
                return response.hits().hits().stream()
                        .map(hit -> hit.source())
                        .filter(item -> item != null)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.debug("ES 长期记忆召回降级（未启动或无匹配记忆）: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 格式化长期记忆为注入 System Prompt 的上下文字符串。
     */
    public String buildMemoryContext(Long userId, String query) {
        List<UserMemoryItem> memories = recallMemories(userId, query, 3);
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【该学生的长期历史画像与个性化记忆】\n");
        for (UserMemoryItem m : memories) {
            sb.append("- ").append(m.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 异步提取并保存长期记忆（识别用户自述的学段、错因、薄弱点、考试目标）。
     */
    @Async
    public void extractAndSaveAsync(Long userId, String message, String sessionId) {
        if (userId == null || message == null || message.length() < 6) {
            return;
        }
        // 快速启发式过滤：包含学员转型/技术背景/目标/薄弱特征词才触发提取
        boolean hasProfileInfo = message.matches(".*(转行|转码|跨行|零基础|非计算机|自学|应届生|跳槽|晋升|提升|后端|前端|全栈|算法|AI|大模型|Python|Java|数据分析|测试|运维|产品|项目经验|技术栈|求职|简历|面试|作品集|经常错|总错|薄弱|不会做|目标|没思路).*");
        if (!hasProfileInfo) {
            return;
        }

        try {
            String system = """
                    你是学员长期学习与职业转型画像提取专家。
                    请分析学员这句话，如果包含学员的【技术背景/学习阶段/转型目标/技术薄弱点/求职诉求】，提取为一句简短客观的陈述（如："非计算机专业想转Java后端开发，目前零基础，对多线程与并发编程感到困难"）。
                    如果不包含任何有价值的长期记忆信息，直接输出 NONE。
                    """;
            LlmUsageHolder usage = new LlmUsageHolder("deepseek-chat", 0);
            String extracted = gateway.callText(system, message, "deepseek-chat", 0.1, usage).trim();

            if (!extracted.equalsIgnoreCase("NONE") && !extracted.isBlank() && extracted.length() < 100) {
                saveMemory(userId, "WEAK_POINT", extracted, sessionId, 4);
                log.info("成功为用户 [{}] 沉淀长期记忆: {}", userId, extracted);
            }
        } catch (Exception e) {
            log.debug("异步提取长期记忆异常: {}", e.getMessage());
        }
    }

    /**
     * 直接保存一条结构化记忆。
     */
    public void saveMemory(Long userId, String type, String content, String sessionId, int importance) {
        try {
            String memoryId = "mem_" + IdUtil.fastSimpleUUID();
            List<Float> vector = embeddingService.embed(content);
            UserMemoryItem item = UserMemoryItem.builder()
                    .memoryId(memoryId)
                    .userId(userId)
                    .memoryType(type)
                    .content(content)
                    .sourceSessionId(sessionId)
                    .importance(importance)
                    .createdAt(System.currentTimeMillis())
                    .lastAccessedAt(System.currentTimeMillis())
                    .embedding(vector)
                    .build();

            esClient.index(i -> i
                    .index(KnowledgeIngestionService.MEMORY_INDEX)
                    .id(memoryId)
                    .document(item)
            );
        } catch (Exception e) {
            log.debug("ES 保存长期记忆异常: {}", e.getMessage());
        }
    }
}
