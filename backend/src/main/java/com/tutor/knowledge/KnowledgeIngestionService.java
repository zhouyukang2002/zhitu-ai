package com.tutor.knowledge;

import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.digest.DigestUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库构建与增量同步服务（Ingestion Pipeline）。
 * 支持 SHA-256 内容哈希防重计算，自动创建 ES 8.x Mapping 并完成向量化 Bulk 批量入库。
 * 中文分词：mapping 由 {@link EsMappingSupport} 统一生成，IK 插件可用时自动启用 ik_max_word/ik_smart。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    public static final String KNOWLEDGE_INDEX = "enterprise_knowledge";
    public static final String MEMORY_INDEX = "user_memory";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;
    private final EsMappingSupport mappingSupport;
    private final AppProperties props;

    /**
     * 启动时或手动调用时执行增量同步。
     */
    public synchronized int syncKnowledgeBase() {
        try {
            ensureIndicesExist();
            List<EnterpriseKnowledgeChunk> chunksToSync = loadAndChunkAllDatasets();
            if (chunksToSync.isEmpty()) {
                return 0;
            }

            BulkRequest.Builder br = new BulkRequest.Builder();
            int count = 0;
            for (EnterpriseKnowledgeChunk chunk : chunksToSync) {
                // 生成配置维度的 Dense 语义向量
                List<Float> vector = embeddingService.embed(chunk.getTitle() + " " + chunk.getText());
                chunk.setEmbedding(vector);

                br.operations(op -> op
                        .index(idx -> idx
                                .index(KNOWLEDGE_INDEX)
                                .id(chunk.getChunkId())
                                .document(chunk)
                        )
                );
                count++;
            }

            BulkResponse result = esClient.bulk(br.build());
            if (result.errors()) {
                log.warn("ES 知识库 Bulk 同步存在局部错误: {}", result.items().size());
            } else {
                log.info("ES 企业私有知识库增量同步成功，共入库 {} 个切片", count);
            }
            return count;
        } catch (Exception e) {
            log.warn("ES 知识库同步异常（ES 未启动或连接失败，将走本地降级）: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 确保 enterprise_knowledge 与 user_memory 索引结构存在。
     */
    public void ensureIndicesExist() {
        try {
            int dims = mappingSupport.vectorDims();
            // 1. enterprise_knowledge 索引 Mapping（中文分词自适应）
            boolean existsKnowledge = esClient.indices().exists(ExistsRequest.of(e -> e.index(KNOWLEDGE_INDEX))).value();
            if (!existsKnowledge) {
                String mappingJson = mappingSupport.knowledgeMapping(dims);
                esClient.indices().create(CreateIndexRequest.of(c -> c
                        .index(KNOWLEDGE_INDEX)
                        .withJson(new ByteArrayInputStream(mappingJson.getBytes(StandardCharsets.UTF_8)))
                ));
                log.info("已创建 ES 索引: {}（分词器: {}）", KNOWLEDGE_INDEX, mappingSupport.indexAnalyzer());
            }

            // 2. user_memory 索引 Mapping（中文分词自适应）
            boolean existsMemory = esClient.indices().exists(ExistsRequest.of(e -> e.index(MEMORY_INDEX))).value();
            if (!existsMemory) {
                String memoryMappingJson = mappingSupport.memoryMapping(dims);
                esClient.indices().create(CreateIndexRequest.of(c -> c
                        .index(MEMORY_INDEX)
                        .withJson(new ByteArrayInputStream(memoryMappingJson.getBytes(StandardCharsets.UTF_8)))
                ));
                log.info("已创建 ES 索引: {}（分词器: {}）", MEMORY_INDEX, mappingSupport.indexAnalyzer());
            }
        } catch (Exception e) {
            log.warn("检查或创建 ES 索引失败: {}", e.getMessage());
        }
    }

    /**
     * 本地降级数据面：语料同步（CorpusSyncService）产出的 knowledge-chunks.json。
     * 未同步时返回空列表（ES 不可用且未同步则检索降级为空）。
     */
    public List<EnterpriseKnowledgeChunk> loadAndChunkAllDatasets() {
        List<EnterpriseKnowledgeChunk> all = new ArrayList<>();
        File chunksFile = new File(props.getDataDir(), "knowledge-chunks.json");
        if (!chunksFile.exists()) {
            return all;
        }
        try {
            List<EnterpriseKnowledgeChunk> list = MAPPER.readValue(
                    FileUtil.readUtf8String(chunksFile), new TypeReference<>() {
                    });
            for (EnterpriseKnowledgeChunk c : list) {
                if (c.getChunkId() == null || c.getChunkId().isBlank()) {
                    continue;
                }
                c.setContentHash(DigestUtil.sha256Hex(c.getTitle() + c.getText()));
                all.add(c);
            }
        } catch (Exception e) {
            log.warn("解析 knowledge-chunks.json 失败: {}", e.getMessage());
        }
        return all;
    }
}
