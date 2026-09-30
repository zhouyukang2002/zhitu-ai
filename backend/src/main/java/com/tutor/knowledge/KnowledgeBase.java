package com.tutor.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.tutor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 企业级 RAG 知识库检索服务（三段式：宽召回 → 重排 → 精取）。
 * 第一阶段：ES 8.x HNSW 向量 + BM25 稀疏检索 + RRF 倒数排名融合，宽召回 recallCandidates 个候选；
 * 第二阶段：{@link RerankService} Cross-Encoder 对候选池精排（未配置 Key 时透传 RRF 原序）；
 * 第三阶段：精取 topK 个知识块进入生成。
 * 双向容灾降级：ES 离线时自动走本地内存知识库（同样经过重排环节），主流程永不中断。
 * 另提供 {@link #searchWithoutRerank} 供检索评测做「开/关重排」A/B 对比。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBase {

    private static final String COURSE_INTRO_INDEX = CorpusSyncService.COURSE_INTRO_INDEX;

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;
    private final KnowledgeIngestionService ingestionService;
    private final RerankService rerankService;
    private final AppProperties props;

    /** 内存降级缓存 */
    private List<EnterpriseKnowledgeChunk> localFallbackChunks = List.of();

    @PostConstruct
    public void init() {
        // 1. 加载本地降级数据
        localFallbackChunks = ingestionService.loadAndChunkAllDatasets();
        log.info("本地内存降级知识库就绪：{} 个切片", localFallbackChunks.size());

        // 2. 尝试向 ES 同步
        ingestionService.syncKnowledgeBase();
    }

    /**
     * 三段式混合检索：Dense 向量 + BM25 + RRF 宽召回 → Cross-Encoder 重排 → 精取 topK。
     */
    public List<Chunk> search(String kp, String query, int topK) {
        String safeQuery = normalizeQuery(kp, query);
        int recallSize = Math.max(props.getAi().getRecallCandidates(), topK);
        List<Chunk> candidates = recallKnowledge(kp, safeQuery, recallSize);
        if (candidates.isEmpty()) {
            return List.of();
        }
        return rerankAndPick(safeQuery, candidates, topK);
    }

    /**
     * 仅宽召回（RRF 原序）直接截取 topK，不经过 Cross-Encoder 重排——
     * 供检索评测脚本做「重排前 vs 重排后」A/B 对比。
     */
    public List<Chunk> searchWithoutRerank(String kp, String query, int topK) {
        String safeQuery = normalizeQuery(kp, query);
        List<Chunk> candidates = recallKnowledge(kp, safeQuery, topK);
        return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
    }

    private String normalizeQuery(String kp, String query) {
        return (query != null && !query.isBlank()) ? query : (kp != null ? kp : "职业技术与课程知识");
    }

    /**
     * 第一阶段宽召回：ES 8.x RRF 混合检索；ES 不可用降级本地 2-gram 加权检索。
     */
    private List<Chunk> recallKnowledge(String kp, String safeQuery, int recallSize) {
        // 1. 优先尝试 ES 8.x 原生 RRF 混合检索
        try {
            List<Float> queryVector = embeddingService.embed(safeQuery);

            SearchResponse<EnterpriseKnowledgeChunk> response = esClient.search(s -> s
                    .index(KnowledgeIngestionService.KNOWLEDGE_INDEX)
                    .knn(knn -> knn
                            .field("embedding")
                            .queryVector(queryVector)
                            .k(20)
                            .numCandidates(50)
                    )
                    .query(q -> q
                            .bool(b -> {
                                if (kp != null && !kp.isBlank()) {
                                    b.should(s1 -> s1.term(t -> t.field("kp").value(kp)));
                                }
                                b.should(s2 -> s2.match(m -> m.field("text").query(safeQuery)));
                                b.should(s3 -> s3.match(m -> m.field("title").query(safeQuery).boost(2.0f)));
                                return b;
                            })
                    )
                    .rank(r -> r.rrf(rrf -> rrf.rankConstant(60L).rankWindowSize(50L)))
                    .size(recallSize),
                    EnterpriseKnowledgeChunk.class
            );

            if (response.hits() != null && response.hits().hits() != null && !response.hits().hits().isEmpty()) {
                List<Chunk> candidates = response.hits().hits().stream()
                        .map(hit -> {
                            var doc = hit.source();
                            if (doc == null) return null;
                            return new Chunk(doc.getKp(), doc.getTitle(), doc.getText(), doc.getSource());
                        })
                        .filter(c -> c != null)
                        .collect(Collectors.toList());
                if (!candidates.isEmpty()) {
                    return candidates;
                }
            }
        } catch (Exception e) {
            log.debug("ES 混合检索走降级链路（ES 尚未就绪或连接中）: {}", e.getMessage());
        }

        // 2. 降级：本地 2-gram 关键词加权检索
        return recallLocal(kp, safeQuery, recallSize);
    }

    /**
     * 课程介绍语义召回（转型规划数据源）：对 course_intro 索引做双路混合检索宽召回，
     * 经 Cross-Encoder 重排后精取。用户目标（"我想做 Java 开发"）匹配候选课程。ES 不可用返回空列表。
     * Chunk.id 携带 courseId（docId），text 含元信息行（层级/价格/目标岗位）。
     */
    public List<Chunk> searchCourses(String goal, int topK) {
        String safeGoal = StrUtil.isNotBlank(goal) ? goal : "职业技能课程";
        int recallSize = Math.max(props.getAi().getRecallCandidates(), topK);
        try {
            List<Float> queryVector = embeddingService.embed(safeGoal);
            SearchResponse<EnterpriseKnowledgeChunk> response = esClient.search(s -> s
                    .index(COURSE_INTRO_INDEX)
                    .knn(knn -> knn.field("embedding").queryVector(queryVector).k(10).numCandidates(50))
                    .query(q -> q.match(m -> m.field("text").query(safeGoal)))
                    .rank(r -> r.rrf(rrf -> rrf.rankConstant(60L).rankWindowSize(20L)))
                    .size(recallSize),
                    EnterpriseKnowledgeChunk.class);
            if (response.hits() != null && response.hits().hits() != null) {
                List<Chunk> candidates = response.hits().hits().stream()
                        .map(hit -> {
                            var doc = hit.source();
                            return doc == null ? null
                                    : new Chunk(doc.getDocId(), doc.getKp(), doc.getTitle(), doc.getText(), doc.getSource());
                        })
                        .filter(c -> c != null)
                        .toList();
                if (!candidates.isEmpty()) {
                    return rerankAndPick(safeGoal, candidates, topK);
                }
            }
        } catch (Exception e) {
            log.warn("课程语义召回降级（course_intro 未就绪）: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 第二阶段精排：Cross-Encoder 对宽召回候选池重排后精取 topK。
     * 候选数 ≤ topK 时重排无意义直接返回；RerankService 未配置 Key 时透传 RRF 原序。
     */
    private List<Chunk> rerankAndPick(String query, List<Chunk> candidates, int topK) {
        if (candidates.size() <= topK) {
            return candidates;
        }
        List<String> docs = candidates.stream()
                .map(c -> c.getTitle() + " " + c.getText())
                .toList();
        List<RerankService.RerankedDoc> order = rerankService.rerank(query, docs, topK);
        List<Chunk> picked = order.stream()
                .map(d -> candidates.get(d.index()))
                .toList();
        if (rerankService.remoteEnabled()) {
            log.debug("RAG 重排：宽召回 {} → 精取 {}（top 分数 {})",
                    candidates.size(), picked.size(),
                    order.isEmpty() ? "-" : String.format("%.3f", order.get(0).score()));
        }
        return picked;
    }

    private List<Chunk> recallLocal(String kp, String query, int recallSize) {
        Set<String> grams = bigrams(query);
        record Scored(Chunk chunk, double score) {}
        List<Scored> scored = new ArrayList<>();

        for (EnterpriseKnowledgeChunk c : localFallbackChunks) {
            double s = 0;
            if (kp != null && kp.equals(c.getKp())) {
                s += 3.0;
            }
            if (c.getTitle() != null && query.contains(c.getTitle())) {
                s += 2.0;
            }
            String titleAndText = (c.getTitle() != null ? c.getTitle() : "") + " " + (c.getText() != null ? c.getText() : "");
            for (String g : grams) {
                if (titleAndText.contains(g)) {
                    s += 0.5;
                }
            }
            if (s > 0) {
                scored.add(new Scored(new Chunk(c.getKp(), c.getTitle(), c.getText(), c.getSource()), s));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Chunk> candidates = scored.stream()
                .limit(recallSize)
                .map(Scored::chunk)
                .collect(Collectors.toList());
        // 本地宽召回后同样走重排环节，保证两条链路行为一致
        return candidates;
    }

    private Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        if (s == null || s.length() < 2) {
            return set;
        }
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class Chunk {
        private String id;
        private String kp;
        private String title;
        private String text;
        private String source;

        public Chunk(String kp, String title, String text, String source) {
            this.id = kp;
            this.kp = kp;
            this.title = title;
            this.text = text;
            this.source = source;
        }
    }
}
