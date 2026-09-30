package com.tutor.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rerank 重排服务（RAG 第二阶段精排）：
 * 第一阶段混合检索（BM25 + 向量 + RRF）是双塔宽召回，速度快但粒度粗；
 * 本服务用 Cross-Encoder 重排模型（如 DashScope gte-rerank-v2）把 query 与每个候选
 * 拼在一起计算细粒度相关性，对宽召回候选池重排后精取 topN。
 *
 * 双模降级（与 EmbeddingService 同构）：
 * - 配置 rerank-api-key 时调用远程 /rerank 端点；
 * - 未配置 Key、调用异常或候选数 ≤1 时，按 RRF 原序截取前 topN（重排退化为透传，不阻断 RAG）。
 */
@Slf4j
@Service
public class RerankService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties props;
    private volatile RestClient restClient;

    public RerankService(AppProperties props) {
        this.props = props;
    }

    /** 重排结果：候选文档在原列表中的下标 + 相关性分数（降序） */
    public record RerankedDoc(int index, double score) {
    }

    /**
     * 对候选文档按与 query 的相关性重排。
     *
     * @param query     检索查询（建议传入查询改写后的独立查询）
     * @param documents 宽召回候选文档文本（顺序即 RRF 融合排名）
     * @param topN      精取数量
     * @return 按相关性降序的下标+分数列表（长度 ≤ topN）；降级时为原序前 topN
     */
    public List<RerankedDoc> rerank(String query, List<String> documents, int topN) {
        int n = documents == null ? 0 : documents.size();
        if (n == 0) {
            return List.of();
        }
        int limit = Math.min(topN, n);

        // 候选只有 1 个时重排无意义，直接返回
        if (n == 1) {
            return List.of(new RerankedDoc(0, 1.0));
        }

        String apiKey = props.getAi().getRerankApiKey();
        String baseUrl = props.getAi().getRerankBaseUrl();
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            return passthrough(n, limit);
        }

        try {
            List<RerankedDoc> ranked = callRemoteRerank(query, documents, limit, baseUrl, apiKey);
            if (ranked != null && !ranked.isEmpty()) {
                return ranked;
            }
        } catch (Exception e) {
            log.warn("调用 Rerank API 异常，按 RRF 原序截取降级: {}", e.getMessage());
        }
        return passthrough(n, limit);
    }

    /** 远程 Cross-Encoder 重排调用（OpenAI 兼容 /rerank 端点） */
    private List<RerankedDoc> callRemoteRerank(String query, List<String> documents, int topN,
                                               String baseUrl, String apiKey) throws Exception {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    restClient = RestClient.builder()
                            .baseUrl(baseUrl)
                            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .build();
                }
            }
        }

        // DashScope 原生 rerank 报文：input{query,documents} + parameters（非 OpenAI 兼容 /rerank，该路径 404）
        Map<String, Object> body = Map.of(
                "model", props.getAi().getRerankModel(),
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("top_n", topN, "return_documents", false)
        );

        String rawJson = restClient.post()
                .uri("/text-rerank")
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = MAPPER.readTree(rawJson);
        JsonNode results = root.path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        List<RerankedDoc> ranked = new ArrayList<>();
        for (JsonNode r : results) {
            int idx = r.path("index").asInt(-1);
            double score = r.path("relevance_score").asDouble(0.0);
            if (idx >= 0 && idx < documents.size()) {
                ranked.add(new RerankedDoc(idx, score));
            }
        }
        return ranked.isEmpty() ? null : ranked;
    }

    /** 降级：按宽召回原序（RRF 排名）截取前 topN */
    private List<RerankedDoc> passthrough(int n, int limit) {
        List<RerankedDoc> order = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            order.add(new RerankedDoc(i, 0.0));
        }
        return order;
    }

    /** 是否启用了远程重排（未配置 Key 时为透传降级模式） */
    public boolean remoteEnabled() {
        String key = props.getAi().getRerankApiKey();
        return key != null && !key.isBlank();
    }
}
