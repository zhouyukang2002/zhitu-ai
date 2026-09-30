package com.tutor.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识冲突与语义重叠前置预警服务（优化点 5）：
 * 在新文档上传解析后、正式入库前，将新切片与既有同类知识库切片做向量相似度比对。
 * 若相似度 >= 0.82 且属于不同文档，则发出潜在政策/内容冲突或冗余告警，提醒教研人员确认或覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeConflictService {

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;

    public record ConflictWarning(
            String newChunkTitle,
            String existingDocId,
            String existingTitle,
            double similarity,
            String alertMessage
    ) {}

    /**
     * 检查新切片集是否存在与现有同分类文档的高度重叠或潜在冲突
     */
    public List<ConflictWarning> detectConflicts(List<DocumentParserService.ParsedChunk> newChunks,
                                                String category, String currentFilename) {
        List<ConflictWarning> warnings = new ArrayList<>();
        if (newChunks == null || newChunks.isEmpty() || !embeddingService.available()) {
            return warnings;
        }

        String indexName = "COURSE".equalsIgnoreCase(category)
                ? CorpusSyncService.COURSE_INTRO_INDEX
                : KnowledgeIngestionService.KNOWLEDGE_INDEX;

        String currentMainName = currentFilename != null ? currentFilename.replace(".md", "").replace(".docx", "").replace(".pdf", "") : "";

        for (var chunk : newChunks) {
            try {
                List<Float> vector = embeddingService.embed(chunk.breadcrumb() + " " + chunk.text());
                SearchResponse<EnterpriseKnowledgeChunk> resp = esClient.search(s -> s
                        .index(indexName)
                        .size(3)
                        .knn(knn -> knn
                                .field("embedding")
                                .queryVector(vector)
                                .k(3)
                                .numCandidates(10)
                        ),
                        EnterpriseKnowledgeChunk.class
                );
                if (resp.hits() != null && resp.hits().hits() != null) {
                    for (var hit : resp.hits().hits()) {
                        Double score = hit.score();
                        EnterpriseKnowledgeChunk src = hit.source();
                        if (score != null && score >= 0.82 && src != null) {
                            String hitDoc = src.getSource() != null ? src.getSource() : src.getDocId();
                            // 排除同名自身的切片（自身属于正常重新解析）
                            if (hitDoc != null && !hitDoc.contains(currentMainName)) {
                                warnings.add(new ConflictWarning(
                                        chunk.title(),
                                        hitDoc,
                                        src.getTitle(),
                                        Math.round(score * 1000.0) / 10.0,
                                        String.format("切片【%s】与现有文档《%s》中的【%s】相似度达 %.1f%%，请核对是否存在政策冲突或冗余",
                                                chunk.title(), hitDoc, src.getTitle(), score * 100)
                                ));
                                break; // 每个新切片只报最严重的一个冲突
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("知识冲突检测跳过: {}", e.getMessage());
            }
        }
        return warnings;
    }
}
