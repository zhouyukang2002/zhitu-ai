package com.tutor.api;

import com.tutor.common.web.Result;
import com.tutor.knowledge.KnowledgeBase;
import com.tutor.prompt.PromptStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词管理端（演示热更新用）：
 * GET /api/admin/prompt 查看当前加载的提示词与长度；
 * POST /api/admin/prompt/reload 手动刷新（配合 GitOps：git pull 后调用）。
 * 另提供检索调试端点（RAG 检索评测/可观测用）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final PromptStore promptStore;
    private final com.tutor.knowledge.KnowledgeIngestionService ingestionService;
    private final com.tutor.knowledge.CorpusSyncService corpusSyncService;
    private final com.tutor.knowledge.SkillDictionary skillDictionary;
    private final com.tutor.client.LocalCourseClient localCourseClient;
    private final com.tutor.knowledge.KnowledgeManageService knowledgeManageService;
    private final KnowledgeBase knowledgeBase;

    /** 知识库资产大盘与健康概览 */
    @GetMapping("/knowledge/overview")
    public Result<Map<String, Object>> knowledgeOverview() {
        return Result.ok(knowledgeManageService.getOverview());
    }

    /** 知识库文档分页列表查询 */
    @GetMapping("/knowledge/list")
    public Result<Map<String, Object>> listKnowledgeDocs(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.ok(knowledgeManageService.listDocuments(category, keyword, page, size));
    }

    /** 知识库单篇文档详情与切片预览 */
    @GetMapping("/knowledge/detail")
    public Result<Map<String, Object>> getKnowledgeDetail(
            @RequestParam("docId") String docId,
            @RequestParam(value = "category", required = false) String category) {
        return Result.ok(knowledgeManageService.getDocumentDetail(docId, category));
    }

    /** 上传多格式文档解析并入库 (支持 .docx/.pdf/.md/.txt) */
    @PostMapping("/knowledge/upload")
    public Result<com.tutor.knowledge.KnowledgeManageService.IngestResult> uploadKnowledgeDoc(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "category", defaultValue = "SKILL") String category,
            @RequestParam(value = "kp", required = false) String kp,
            @RequestParam(value = "source", required = false) String source) throws Exception {
        return Result.ok(knowledgeManageService.uploadAndIngest(file, category, kp, source));
    }

    /** 级联物理删除文档及其在 ES 中的切片 */
    @org.springframework.web.bind.annotation.DeleteMapping("/knowledge/delete")
    public Result<Map<String, Object>> deleteKnowledgeDoc(
            @RequestParam("docId") String docId,
            @RequestParam(value = "category", required = false) String category) {
        boolean ok = knowledgeManageService.deleteDocument(docId, category);
        return Result.ok(Map.of("deleted", ok, "docId", docId));
    }

    /** RAG 工业级质量三元组与 APM 延迟指标 */
    @GetMapping("/knowledge/rag-metrics")
    public Result<Map<String, Object>> getRagMetrics() {
        return Result.ok(knowledgeManageService.getRagMetrics());
    }

    @GetMapping("/prompt")
    public Result<Map<String, Integer>> list() {
        return Result.ok(promptStore.snapshot());
    }

    @PostMapping("/prompt/reload")
    public Result<Map<String, Integer>> reload() {
        promptStore.reload();
        return Result.ok(promptStore.snapshot());
    }

    /** 一键增量同步企业知识库到 ES 8.x */
    @PostMapping("/knowledge/sync")
    public Result<Map<String, Object>> syncKnowledge() {
        int count = ingestionService.syncKnowledgeBase();
        return Result.ok(Map.of("syncedCount", count, "status", "SUCCESS"));
    }

    /**
     * 语料同步（MD 语料库 → ES + 本地数据面）：
     * 重建 enterprise_knowledge 与 course_intro 索引、刷新本地降级 JSON 与 kp 词典。
     * MySQL 侧（course_info/question_bank）由 MCP 端 POST :8081/api/admin/corpus/sync 负责。
     */
    @PostMapping("/corpus/sync")
    public Result<Map<String, Object>> syncCorpus() {
        Map<String, Object> result = corpusSyncService.syncAll();
        skillDictionary.reload();
        localCourseClient.reload();
        // 联动触发 MCP 端点（:8081）刷新 MySQL 课程/题库与内存数据面
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:8081/api/admin/corpus/sync"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            result.put("mcpSync", resp.statusCode() == 200 ? "SUCCESS" : ("HTTP " + resp.statusCode()));
        } catch (Exception e) {
            log.debug("MCP 端语料同步触发跳过: {}", e.getMessage());
            result.put("mcpSync", "SKIPPED: " + e.getMessage());
        }
        return Result.ok(result);
    }

    /**
     * 检索调试端点（RAG 检索评测用）：返回三段式检索精取后的知识块元数据。
     * rerank=false 时跳过 Cross-Encoder 重排，直接取 RRF 原序前 topK，用于「重排前后」A/B 对比。
     * 只返回 kp/title/source 元数据，不返回全文，控制响应体积。
     */
    @GetMapping("/retrieval/search")
    public Result<List<Map<String, Object>>> retrievalSearch(
            @RequestParam("query") String query,
            @RequestParam(value = "kp", required = false) String kp,
            @RequestParam(value = "topK", defaultValue = "5") int topK,
            @RequestParam(value = "rerank", defaultValue = "true") boolean rerank) {
        List<KnowledgeBase.Chunk> chunks = rerank
                ? knowledgeBase.search(kp, query, topK)
                : knowledgeBase.searchWithoutRerank(kp, query, topK);
        List<Map<String, Object>> meta = chunks.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kp", c.getKp());
            m.put("title", c.getTitle());
            m.put("source", c.getSource());
            return m;
        }).toList();
        return Result.ok(meta);
    }
}
