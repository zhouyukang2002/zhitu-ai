package com.tutor.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tutor.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ES 索引 Mapping 统一工厂 + 中文分词器自适应。
 *
 * 背景：ES 默认 standard 分析器对中文按单字切分（"集合框架"→集/合/框/架），
 * 导致 BM25 这一路召回大量无关文档；安装 analysis-ik 插件后应使用
 * ik_max_word（索引时细粒度）+ ik_smart（查询时粗粒度）。
 *
 * 自适应策略：首次建索引时用 _analyze 探测 IK 是否可用——
 * 可用则用 IK，不可用回退 standard，保证未装插件也能正常启动（只打 WARN）。
 */
@Slf4j
@Component
public class EsMappingSupport {

    private final ObjectProvider<ElasticsearchClient> esClientProvider;
    private final AppProperties props;
    /** IK 可用性只探测一次（null=尚未探测） */
    private volatile Boolean ikAvailable = null;

    public EsMappingSupport(ObjectProvider<ElasticsearchClient> esClientProvider, AppProperties props) {
        this.esClientProvider = esClientProvider;
        this.props = props;
    }

    /** IK 中文分词器是否可用（懒探测，结果缓存） */
    public boolean ikAvailable() {
        Boolean cached = ikAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (ikAvailable != null) {
                return ikAvailable;
            }
            ikAvailable = detectIk();
            return ikAvailable;
        }
    }

    private boolean detectIk() {
        try {
            ElasticsearchClient client = esClientProvider.getIfAvailable();
            if (client == null) {
                return false;
            }
            // 用 ik_smart 分析一个中文词，成功即说明 analysis-ik 插件已安装
            client.indices().analyze(a -> a.analyzer("ik_smart").text("中文分词测试"));
            log.info("ES IK 分词器可用：BM25 中文分析器使用 ik_max_word(索引)/ik_smart(查询)");
            return true;
        } catch (Exception e) {
            log.warn("ES 未检测到 IK 分词器插件，BM25 回退 standard（中文按单字切分会影响全文召回精度，"
                    + "建议安装 analysis-ik：bin/elasticsearch-plugin install https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-{es版本}.zip）");
            return false;
        }
    }

    /**
     * 知识库/课程介绍统一 mapping（HNSW 向量 + 自适应中文分词）。
     *
     * @param dims 向量维度
     */
    public String knowledgeMapping(int dims) {
        boolean ik = ikAvailable();
        String indexAnalyzer = ik ? "ik_max_word" : "standard";
        String searchAnalyzer = ik ? "ik_smart" : "standard";
        return """
                {
                  "mappings": {
                    "properties": {
                      "chunkId": { "type": "keyword" },
                      "docId": { "type": "keyword" },
                      "category": { "type": "keyword" },
                      "kp": { "type": "keyword" },
                      "title": { "type": "text", "analyzer": "%s", "search_analyzer": "%s" },
                      "text": { "type": "text", "analyzer": "%s", "search_analyzer": "%s" },
                      "source": { "type": "keyword" },
                      "contentHash": { "type": "keyword" },
                      "embedding": {
                        "type": "dense_vector",
                        "dims": %d,
                        "index": true,
                        "similarity": "cosine",
                        "index_options": { "type": "hnsw", "m": 16, "ef_construction": 100 }
                      }
                    }
                  }
                }
                """.formatted(indexAnalyzer, searchAnalyzer, indexAnalyzer, searchAnalyzer, dims);
    }

    /** 向量维度（取配置，缺省 1024） */
    public int vectorDims() {
        int dims = props.getAi().getEmbeddingDimensions();
        return dims > 0 ? dims : EmbeddingService.DEFAULT_DIMENSIONS;
    }

    /** 索引时分词器（IK 可用时 ik_max_word，否则 standard） */
    public String indexAnalyzer() {
        return ikAvailable() ? "ik_max_word" : "standard";
    }

    /** 查询时分词器（IK 可用时 ik_smart，否则 standard） */
    public String searchAnalyzer() {
        return ikAvailable() ? "ik_smart" : "standard";
    }

    /**
     * 长期记忆 user_memory 索引 mapping（结构与知识库不同，content 字段同样自适应中文分词）。
     */
    public String memoryMapping(int dims) {
        return """
                {
                  "mappings": {
                    "properties": {
                      "memoryId": { "type": "keyword" },
                      "userId": { "type": "long" },
                      "memoryType": { "type": "keyword" },
                      "content": { "type": "text", "analyzer": "%s", "search_analyzer": "%s" },
                      "sourceSessionId": { "type": "keyword" },
                      "importance": { "type": "integer" },
                      "createdAt": { "type": "long" },
                      "lastAccessedAt": { "type": "long" },
                      "embedding": {
                        "type": "dense_vector",
                        "dims": %d,
                        "index": true,
                        "similarity": "cosine",
                        "index_options": { "type": "hnsw", "m": 16, "ef_construction": 100 }
                      }
                    }
                  }
                }
                """.formatted(indexAnalyzer(), searchAnalyzer(), dims);
    }
}
