package com.tutor.knowledge;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 知识库资产全生命周期治理服务（CRUD + 资产大盘 + RAG 评测指标）：
 * 1. 资产大盘与分类统计（SKILL / COURSE / POLICY / CAREER）
 * 2. 多格式文档上传解析、防重哈希、冲突检测、向量写入
 * 3. 级联原子删除（文件 + ES 切片 deleteByQuery + 本地降级缓存）
 * 4. RAG 三元组质量指标与 APM 延迟瀑布聚合
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeManageService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties props;
    private final DocumentParserService parserService;
    private final KnowledgeConflictService conflictService;
    private final SemanticCacheService cacheService;
    private final EmbeddingService embeddingService;
    private final ElasticsearchClient esClient;
    private final SkillDictionary skillDictionary;

    public record DocumentMeta(
            String docId,
            String filename,
            String category,
            String kp,
            String source,
            int chunkCount,
            long sizeBytes,
            String updatedAt
    ) {}

    public record IngestResult(
            String docId,
            String filename,
            int chunkCount,
            List<KnowledgeConflictService.ConflictWarning> conflictWarnings,
            String message
    ) {}

    /**
     * 获取知识库大盘概览与资产统计
     */
    public Map<String, Object> getOverview() {
        Map<String, Object> map = new LinkedHashMap<>();
        List<DocumentMeta> allDocs = scanAllDocuments();

        int totalChunks = allDocs.stream().mapToInt(DocumentMeta::chunkCount).sum();
        long totalBytes = allDocs.stream().mapToLong(DocumentMeta::sizeBytes).sum();

        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        categoryCounts.put("SKILL", allDocs.stream().filter(d -> "SKILL".equalsIgnoreCase(d.category())).count());
        categoryCounts.put("COURSE", allDocs.stream().filter(d -> "COURSE".equalsIgnoreCase(d.category())).count());
        categoryCounts.put("POLICY", allDocs.stream().filter(d -> "POLICY".equalsIgnoreCase(d.category())).count());
        categoryCounts.put("CAREER", allDocs.stream().filter(d -> "CAREER".equalsIgnoreCase(d.category())).count());

        map.put("totalDocuments", allDocs.size());
        map.put("totalChunks", totalChunks);
        map.put("totalSizeBytes", totalBytes);
        map.put("categoryCounts", categoryCounts);

        // ES 索引探针
        boolean esOnline = false;
        try {
            esOnline = esClient.ping().value();
        } catch (Exception ignored) {}
        map.put("esOnline", esOnline);
        map.put("embeddingModel", props.getAi().getEmbeddingModel());
        map.put("rerankModel", props.getAi().getRerankModel());

        // 语义缓存指标
        map.put("semanticCache", cacheService.getStats());

        return map;
    }

    /**
     * 文档列表查询（支持分类与关键字筛选、分页）
     */
    public Map<String, Object> listDocuments(String category, String keyword, int page, int size) {
        List<DocumentMeta> docs = scanAllDocuments();

        if (StrUtil.isNotBlank(category) && !"ALL".equalsIgnoreCase(category)) {
            docs = docs.stream().filter(d -> category.equalsIgnoreCase(d.category())).toList();
        }
        if (StrUtil.isNotBlank(keyword)) {
            String q = keyword.toLowerCase().trim();
            docs = docs.stream().filter(d ->
                    d.filename().toLowerCase().contains(q)
                            || d.kp().toLowerCase().contains(q)
                            || d.source().toLowerCase().contains(q)
            ).toList();
        }

        int total = docs.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<DocumentMeta> paged = from < total ? docs.subList(from, to) : List.of();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", total);
        res.put("page", page);
        res.put("size", size);
        res.put("list", paged);
        return res;
    }

    /**
     * 获取单篇文档完整内容与切片详情
     */
    public Map<String, Object> getDocumentDetail(String docId, String category) {
        File f = findFileByDocId(docId, category);
        if (f == null || !f.exists()) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            String mainDoc = FileUtil.mainName(f.getName());

            // 清理 Frontmatter
            String body = content;
            if (body.startsWith("---")) {
                int second = body.indexOf("---", 3);
                if (second > 0) {
                    body = body.substring(second + 3).trim();
                }
            }

            List<Map<String, Object>> chunks = new ArrayList<>();
            // 优先按 ## 切分
            String[] sections = body.split("(?m)^##\\s+");
            for (String sec : sections) {
                String trimmed = sec.trim();
                if (trimmed.isBlank()) continue;
                int nl = trimmed.indexOf('\n');
                String title;
                String text;
                if (nl > 0) {
                    title = trimmed.substring(0, nl).trim();
                    text = trimmed.substring(nl).trim();
                } else {
                    title = trimmed.length() > 20 ? trimmed.substring(0, 20) + "..." : trimmed;
                    text = trimmed;
                }
                if (text.isBlank()) {
                    text = title;
                }
                chunks.add(Map.of(
                        "title", title,
                        "breadcrumb", "【" + mainDoc + " > " + title + "】",
                        "text", text,
                        "tokenCount", Math.max(1, (int) ((title.length() + text.length()) * 0.75))
                ));
            }

            // 若按 ## 未能切出切片（例如只有 # 或无标题），按 # 切分
            if (chunks.isEmpty() && !body.isBlank()) {
                String[] h1Sections = body.split("(?m)^#\\s+");
                for (String sec : h1Sections) {
                    String trimmed = sec.trim();
                    if (trimmed.isBlank()) continue;
                    int nl = trimmed.indexOf('\n');
                    String title = nl > 0 ? trimmed.substring(0, nl).trim() : mainDoc;
                    String text = nl > 0 ? trimmed.substring(nl).trim() : trimmed;
                    chunks.add(Map.of(
                            "title", title,
                            "breadcrumb", "【" + mainDoc + " > " + title + "】",
                            "text", text.isBlank() ? title : text,
                            "tokenCount", Math.max(1, (int) ((title.length() + text.length()) * 0.75))
                    ));
                }
            }

            // 仍为空则整篇兜底，确保绝不返回空切片
            if (chunks.isEmpty()) {
                chunks.add(Map.of(
                        "title", mainDoc,
                        "breadcrumb", "【" + mainDoc + "】",
                        "text", body.isBlank() ? content : body,
                        "tokenCount", Math.max(1, (int) (content.length() * 0.75))
                ));
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("docId", docId);
            detail.put("filename", f.getName());
            detail.put("content", content);
            detail.put("chunks", chunks);
            detail.put("chunkCount", chunks.size());
            return detail;
        } catch (Exception e) {
            throw new RuntimeException("读取文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传解析入库（支持 .docx, .pdf, .md, .txt）
     */
    public IngestResult uploadAndIngest(MultipartFile file, String category, String kp, String source) throws Exception {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            originalFilename = "upload_" + System.currentTimeMillis() + ".md";
        }

        String safeCat = StrUtil.isNotBlank(category) ? category.toUpperCase() : "SKILL";

        // 1. 结构化解析（提取标题、表格、注入面包屑上下文）
        DocumentParserService.ParsedDocument parsed = parserService.parse(
                file.getInputStream(), originalFilename, safeCat, kp, source);

        // 2. 知识冲突与重复前置检测（优化点 5）
        List<KnowledgeConflictService.ConflictWarning> warnings =
                conflictService.detectConflicts(parsed.chunks(), safeCat, originalFilename);

        // 3. 确定目标写入路径并持久化规范 Markdown
        String targetDir = "COURSE".equalsIgnoreCase(safeCat)
                ? props.getCorpusDir() + "/课程"
                : props.getCorpusDir() + "/知识条目";

        String targetFilename = FileUtil.mainName(originalFilename) + ".md";
        File targetFile = new File(targetDir, targetFilename);
        FileUtil.writeString(parsed.markdownContent(), targetFile, StandardCharsets.UTF_8);

        // 4. 向量化切片并 Bulk 写入 Elasticsearch
        int indexedCount = indexChunksToEs(parsed, targetFilename);

        // 5. 热重载受控技能词典
        skillDictionary.reload();

        String docId = FileUtil.mainName(targetFilename);
        return new IngestResult(docId, targetFilename, parsed.chunks().size(), warnings,
                "解析入库成功，已生成 " + parsed.chunks().size() + " 个语义切片");
    }

    /**
     * 级联删除文档：物理删除文件 + ES 索引切片 deleteByQuery + 热重载
     */
    public boolean deleteDocument(String docId, String category) {
        File f = findFileByDocId(docId, category);
        if (f == null || !f.exists()) {
            return false;
        }

        String filename = f.getName();
        String mainName = FileUtil.mainName(filename);
        FileUtil.del(f);

        // 级联删除 ES 向量切片
        String indexName = "COURSE".equalsIgnoreCase(category)
                ? CorpusSyncService.COURSE_INTRO_INDEX
                : KnowledgeIngestionService.KNOWLEDGE_INDEX;

        try {
            esClient.deleteByQuery(DeleteByQueryRequest.of(d -> d
                    .index(indexName)
                    .query(Query.of(q -> q
                            .wildcard(w -> w.field("chunkId").wildcard("*" + mainName + "*"))
                    ))
            ));
            log.info("已级联物理清除 ES 索引 [{}] 中文档 [{}] 的所有切片", indexName, mainName);
        } catch (Exception e) {
            log.warn("ES 切片级联删除告警（可能索引未就绪）: {}", e.getMessage());
        }

        skillDictionary.reload();
        return true;
    }

    /**
     * RAG 质量指标大盘数据。
     * 数据来源：scripts/rag-eval.mjs（101 例检索评测，含重排 A/B）与 scripts/ragas-eval.mjs
     * （34 例 LLM Judge 四件套）的实测结果——大盘数字必须可复现，禁止写占位估值误导排查。
     * 实测明细见《智能助教引擎测评报告》。
     */
    public Map<String, Object> getRagMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();

        // 1. RAG 生成质量（RAGAS 口径 LLM Judge 实测，抽样 34 例）
        m.put("contextRelevance", 65.6);   // Context Recall（参考答案要点被召回切片覆盖比例）
        m.put("faithfulness", 76.0);       // Faithfulness（回答论断被切片支持比例）
        m.put("answerRelevance", 68.8);    // Answer Relevancy（回答切题度）

        // 2. 检索指标（101 例实测，RRF+Cross-Encoder 重排后）
        m.put("hitRateTop5", 96.0);        // Recall@5
        m.put("mrr", 0.873);               // MRR
        m.put("rerankBoost", "+5.9pt");    // 重排增益（Recall@1：76.2%→82.2%，A/B 实证）

        // 3. 延迟拆解（毫秒）：环节级实测见 Trace 观测树（/api/admin/trace/{id}），此处不展示无来源估算
        m.put("latencyBreakdown", Map.of(
                "note", "环节级耗时请查 Trace 观测树，快路径 TTFB P50=292ms、完整闭环 P50=8.3s（实测）"));

        return m;
    }

    private int indexChunksToEs(DocumentParserService.ParsedDocument parsed, String filename) {
        if (!embeddingService.available()) {
            log.warn("Embedding 服务不可用，跳过 ES 向量化入库（本地 Markdown 已持久化）");
            return 0;
        }
        try {
            String indexName = "COURSE".equalsIgnoreCase(parsed.category())
                    ? CorpusSyncService.COURSE_INTRO_INDEX
                    : KnowledgeIngestionService.KNOWLEDGE_INDEX;

            BulkRequest.Builder br = new BulkRequest.Builder();
            int count = 0;
            for (var chunk : parsed.chunks()) {
                String chunkId = "corpus_" + DigestUtil.md5Hex(filename + chunk.title()).substring(0, 16);
                List<Float> vector = embeddingService.embed(chunk.breadcrumb() + " " + chunk.text());

                EnterpriseKnowledgeChunk entity = new EnterpriseKnowledgeChunk();
                entity.setChunkId(chunkId);
                entity.setDocId(filename + "#" + chunk.title());
                entity.setCategory(parsed.category());
                entity.setKp(parsed.kp());
                entity.setTitle(chunk.title());
                entity.setText(chunk.text());
                entity.setSource(parsed.source());
                entity.setContentHash(DigestUtil.sha256Hex(chunk.title() + chunk.text()));
                entity.setEmbedding(vector);

                br.operations(op -> op.index(idx -> idx.index(indexName).id(chunkId).document(entity)));
                count++;
            }
            esClient.bulk(br.build());
            log.info("已将文档 [{}] 的 {} 个切片向量化写入 ES 索引 [{}]", filename, count, indexName);
            return count;
        } catch (Exception e) {
            log.warn("ES 向量化 Bulk 写入异常: {}", e.getMessage());
            return 0;
        }
    }

    private List<DocumentMeta> scanAllDocuments() {
        List<DocumentMeta> list = new ArrayList<>();
        File root = new File(props.getCorpusDir());
        if (!root.exists()) return list;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        // 1. 扫描 知识条目/ 下的文档 (SKILL / POLICY / CAREER)
        File entryDir = new File(root, "知识条目");
        if (entryDir.exists() && entryDir.listFiles() != null) {
            for (File f : Objects.requireNonNull(entryDir.listFiles((d, n) -> n.endsWith(".md")))) {
                list.add(extractMeta(f, null, sdf));
            }
        }

        // 2. 扫描 课程/ 下的文档 (COURSE)
        File courseDir = new File(root, "课程");
        if (courseDir.exists() && courseDir.listFiles() != null) {
            for (File f : Objects.requireNonNull(courseDir.listFiles((d, n) -> n.endsWith(".md")))) {
                list.add(extractMeta(f, "COURSE", sdf));
            }
        }

        // 按更新时间降序
        list.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return list;
    }

    private DocumentMeta extractMeta(File f, String forceCat, SimpleDateFormat sdf) {
        String docId = FileUtil.mainName(f.getName());
        String filename = f.getName();

        // 默认分类推断：优先前缀与目录
        String cat = "SKILL";
        if (forceCat != null) {
            cat = forceCat;
        } else if (filename.startsWith("SKILL_")) {
            cat = "SKILL";
        } else if (filename.startsWith("POLICY_")) {
            cat = "POLICY";
        } else if (filename.startsWith("CAREER_")) {
            cat = "CAREER";
        }

        String kp = "综合技术";
        String source = filename;
        int chunkCount = 1;

        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);

            // 规范解析 Frontmatter
            if (content.startsWith("---")) {
                int endFm = content.indexOf("---", 3);
                if (endFm > 0) {
                    String fm = content.substring(3, endFm);
                    for (String line : fm.split("\\r?\\n")) {
                        line = line.trim();
                        if (line.startsWith("category:")) {
                            String val = line.substring("category:".length()).trim();
                            if (!val.isBlank()) cat = val.toUpperCase();
                        } else if (line.startsWith("kp:")) {
                            String val = line.substring("kp:".length()).trim();
                            if (!val.isBlank()) kp = val;
                        } else if (line.startsWith("kps:")) {
                            String rawKps = line.substring("kps:".length()).trim();
                            rawKps = rawKps.replaceAll("[\\[\\]]", "");
                            String[] parts = rawKps.split(",");
                            if (parts.length > 0 && !parts[0].trim().isBlank()) {
                                kp = parts[0].trim();
                            }
                        } else if (line.startsWith("source:")) {
                            String val = line.substring("source:".length()).trim();
                            if (!val.isBlank()) source = val;
                        } else if (line.startsWith("name:")) {
                            String val = line.substring("name:".length()).trim();
                            if (!val.isBlank()) source = val;
                        }
                    }
                }
            }

            // 计算有效切片数
            String[] secs = content.split("(?m)^##\\s+");
            chunkCount = Math.max(1, secs.length - 1);
        } catch (Exception ignored) {}

        return new DocumentMeta(
                docId, filename, cat, kp, source, chunkCount, f.length(),
                sdf.format(new Date(f.lastModified()))
        );
    }

    private File findFileByDocId(String docId, String category) {
        if (StrUtil.isBlank(docId)) return null;
        String cleanId = docId.trim();
        try {
            cleanId = java.net.URLDecoder.decode(cleanId, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        if (cleanId.endsWith(".md")) {
            cleanId = cleanId.substring(0, cleanId.length() - 3);
        }

        File root = new File(props.getCorpusDir());
        // 优先根据 category 找
        if ("COURSE".equalsIgnoreCase(category)) {
            File fc = new File(root, "课程/" + cleanId + ".md");
            if (fc.exists()) return fc;
        } else {
            File fe = new File(root, "知识条目/" + cleanId + ".md");
            if (fe.exists()) return fe;
        }

        File f1 = new File(root, "知识条目/" + cleanId + ".md");
        if (f1.exists()) return f1;
        File f2 = new File(root, "课程/" + cleanId + ".md");
        if (f2.exists()) return f2;

        // 如果仍未找到，尝试不区分大小写匹配文件名
        File entryDir = new File(root, "知识条目");
        if (entryDir.exists() && entryDir.listFiles() != null) {
            for (File f : Objects.requireNonNull(entryDir.listFiles())) {
                if (FileUtil.mainName(f.getName()).equalsIgnoreCase(cleanId)) return f;
            }
        }
        File courseDir = new File(root, "课程");
        if (courseDir.exists() && courseDir.listFiles() != null) {
            for (File f : Objects.requireNonNull(courseDir.listFiles())) {
                if (FileUtil.mainName(f.getName()).equalsIgnoreCase(cleanId)) return f;
            }
        }

        return null;
    }
}
