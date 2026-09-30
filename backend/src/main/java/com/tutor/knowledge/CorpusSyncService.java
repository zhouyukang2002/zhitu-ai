package com.tutor.knowledge;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 语料同步服务（引擎侧摄入管线）：MD 语料库 → ES 语义索引 + 本地降级数据面。
 * 与 MCP 端摄入（MD → tutor_biz MySQL）同源配合，保证课程介绍（ES）、
 * 课程主数据/题库（MySQL）、本地降级 JSON（data/）三处数据一致。
 * 产物：
 * 1) enterprise_knowledge 索引重建（SKILL/CAREER/POLICY 知识条目）
 * 2) course_intro 索引重建（课程介绍，转型规划语义召回源）
 * 3) data/knowledge-chunks.json、data/courses.json、data/question-bank.json、data/kp-dictionary.json
 * 中文分词：索引 mapping 由 {@link EsMappingSupport} 统一生成（IK 可用时自动启用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorpusSyncService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern Q_HEADER = Pattern.compile("^##\\s+(q_[A-Za-z0-9_]+)\\s*$");
    private static final Pattern FIELD_LINE = Pattern.compile("^-\\s+([A-Za-z]+)\\s*:\\s*(.*)$");

    public static final String COURSE_INTRO_INDEX = "course_intro";

    private final co.elastic.clients.elasticsearch.ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;
    private final EsMappingSupport mappingSupport;
    private final AppProperties props;

    public Map<String, Object> syncAll() {
        File corpusDir = new File(props.getCorpusDir());
        Map<String, Object> result = new HashMap<>();
        List<String> allKps = new ArrayList<>();
        Map<String, String> allAliases = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        // 失败如实进结果：降级可以吞异常，但状态必须传播——否则调用方误以为索引已重建
        result.put("knowledgeChunks", syncStage("enterprise_knowledge", errors, () -> syncKnowledgeEntries(corpusDir, allKps, allAliases)));
        result.put("courseIntros", syncStage("course_intro", errors, () -> syncCourseIntros(corpusDir, allKps, allAliases)));
        result.put("questions", writeLocalFallbacks(corpusDir, allKps, allAliases));
        result.put("kpDictionary", allKps.stream().distinct().sorted().count());
        result.put("aliasesCount", allAliases.size());
        result.put("errors", errors);
        result.put("status", errors.isEmpty() ? "SUCCESS" : "PARTIAL_FAILURE");
        return result;
    }

    /** 单阶段执行包装：异常收敛为 errors 条目，返回 -1 表示该阶段未完成 */
    private int syncStage(String index, List<String> errors, java.util.function.IntSupplier stage) {
        try {
            return stage.getAsInt();
        } catch (Exception e) {
            log.error("ES 索引 {} 重建失败: {}", index, e.getMessage(), e);
            errors.add(index + ": " + e.getMessage());
            return -1;
        }
    }

    /** 知识条目 → 重建 enterprise_knowledge + data/knowledge-chunks.json */
    private int syncKnowledgeEntries(File corpusDir, List<String> allKps, Map<String, String> allAliases) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        File dir = new File(corpusDir, "知识条目");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        if (files != null) {
            for (File f : files) {
                try {
                    String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    Parsed parsed = splitFrontmatter(content);
                    String kp = StrUtil.toStringOrNull(parsed.frontmatter().get("kp"));
                    String category = StrUtil.toStringOrNull(parsed.frontmatter().get("category"));
                    String source = StrUtil.toStringOrNull(parsed.frontmatter().get("source"));
                    if (kp == null) {
                        continue;
                    }
                    Object rawAliases = parsed.frontmatter().get("aliases");
                    if (rawAliases != null) {
                        if (rawAliases instanceof Map<?, ?> m) {
                            m.forEach((k, v) -> allAliases.put(String.valueOf(k).toLowerCase().trim(), String.valueOf(v).trim()));
                        } else if (rawAliases instanceof List<?> list) {
                            list.forEach(a -> allAliases.put(String.valueOf(a).toLowerCase().trim(), kp.trim()));
                        }
                    }
                    boolean isCareer = "CAREER".equals(category);
                    for (Map.Entry<String, String> section : parsed.sections().entrySet()) {
                        // CAREER 条目：每个章节标题即岗位名（一文件多岗位）；SKILL/POLICY 用文件级 kp
                        String chunkKp = isCareer ? section.getKey() : kp;
                        Map<String, Object> chunk = new LinkedHashMap<>();
                        chunk.put("chunkId", "corpus_" + DigestUtil.md5Hex(f.getName() + section.getKey()).substring(0, 16));
                        chunk.put("docId", f.getName().replace(".md", "") + "#" + section.getKey());
                        chunk.put("category", category == null ? "SKILL" : category);
                        chunk.put("kp", chunkKp);
                        chunk.put("title", section.getKey());
                        chunk.put("text", section.getValue());
                        chunk.put("source", source == null ? f.getName() : source);
                        chunk.put("contentHash", DigestUtil.sha256Hex(section.getKey() + section.getValue()));
                        chunks.add(chunk);
                        allKps.add(kp);
                    }
                } catch (Exception e) {
                    log.warn("知识条目解析失败: {}: {}", f.getName(), e.getMessage());
                }
            }
        }
        rebuildIndex(KnowledgeIngestionService.KNOWLEDGE_INDEX, chunks);
        try {
            MAPPER.writeValue(new File(props.getDataDir(), "knowledge-chunks.json"), chunks);
        } catch (Exception e) {
            log.warn("knowledge-chunks.json 写入失败: {}", e.getMessage());
        }
        return chunks.size();
    }

    /** 课程文件 → 重建 course_intro（介绍+元信息拼接，供转型规划语义召回）+ data/courses.json */
    private int syncCourseIntros(File corpusDir, List<String> allKps, Map<String, String> allAliases) {
        List<Map<String, Object>> docs = new ArrayList<>();
        List<Map<String, Object>> localCourses = new ArrayList<>();
        List<String> kps = new ArrayList<>();
        File dir = new File(corpusDir, "课程");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        if (files != null) {
            for (File f : files) {
                try {
                    String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    Parsed parsed = splitFrontmatter(content);
                    Map<String, Object> fm = parsed.frontmatter();
                    String courseId = StrUtil.toStringOrNull(fm.get("courseId"));
                    String name = StrUtil.toStringOrNull(fm.get("name"));
                    String intro = parsed.sections().getOrDefault("课程介绍", "");
                    String outline = parsed.sections().getOrDefault("课程大纲", "");
                    if (courseId == null || name == null || intro.isBlank()) {
                        continue;
                    }
                    Object rawAliases = fm.get("aliases");
                    if (rawAliases != null) {
                        if (rawAliases instanceof Map<?, ?> m) {
                            m.forEach((k, v) -> allAliases.put(String.valueOf(k).toLowerCase().trim(), String.valueOf(v).trim()));
                        } else if (rawAliases instanceof List<?> list) {
                            list.forEach(a -> allAliases.put(String.valueOf(a).toLowerCase().trim(), name.trim()));
                        }
                    }
                    List<String> courseKps = stringList(fm.get("kps"));
                    kps.addAll(courseKps);
                    allKps.addAll(courseKps);
                    List<String> roles = stringList(fm.get("roles"));
                    String level = StrUtil.toStringOrNull(fm.get("level"));
                    String track = StrUtil.toStringOrNull(fm.get("track"));
                    int price = intOf(fm.get("price"));
                    String meta = "\n\n[元信息] 层级：" + StrUtil.nullToDefault(level, "入门")
                            + " ｜ 价格：" + price + " 元 ｜ 目标岗位：" + String.join("、", roles);
                    String text = intro + meta;

                    Map<String, Object> doc = new HashMap<>();
                    doc.put("chunkId", "intro_" + courseId);
                    doc.put("docId", courseId);
                    doc.put("category", "COURSE_INTRO");
                    doc.put("kp", name);
                    doc.put("title", name);
                    doc.put("text", text);
                    doc.put("source", "《课程中心·" + name + "》");
                    doc.put("contentHash", DigestUtil.sha256Hex(name + text));
                    doc.put("embedding", embeddingService.embed(name + " " + text));
                    docs.add(doc);

                    Map<String, Object> localCourse = new LinkedHashMap<>();
                    localCourse.put("courseId", courseId);
                    localCourse.put("name", name);
                    localCourse.put("price", price);
                    List<String> tags = new ArrayList<>();
                    if (track != null) tags.add(track);
                    if (level != null) tags.add(level);
                    tags.addAll(roles);
                    localCourse.put("tags", tags);
                    localCourse.put("reason", StrUtil.sub(intro.replaceAll("\n", " "), 0, 60) + "…");
                    localCourses.add(localCourse);

                    // 课程大纲顺带写入本地（MCP 端负责 MySQL 写入，此处仅保证本地 fallback 齐全）
                    writeCourseOutline(courseId, name, outline);
                } catch (Exception e) {
                    log.warn("课程语料解析失败: {}: {}", f.getName(), e.getMessage());
                }
            }
        }
        rebuildIndex(COURSE_INTRO_INDEX, docs);
        try {
            MAPPER.writeValue(new File(props.getDataDir(), "courses.json"), localCourses);
        } catch (Exception e) {
            log.warn("courses.json 写入失败: {}", e.getMessage());
        }
        return docs.size();
    }

    /** 题库文件 → data/question-bank.json（本地降级题库，含答案键）+ 汇总 kp 词典 */
    private int writeLocalFallbacks(File corpusDir, List<String> allKps, Map<String, String> allAliases) {
        List<Map<String, Object>> localQuestions = new ArrayList<>();
        File bankDir = new File(corpusDir, "题库");
        File[] files = bankDir.listFiles((d, name) -> name.endsWith(".md"));
        if (files != null) {
            for (File f : files) {
                try {
                    String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    Parsed parsed = splitFrontmatter(content);
                    String courseId = StrUtil.toStringOrNull(parsed.frontmatter().get("courseId"));
                    if (courseId == null) {
                        continue;
                    }
                    for (Map<String, String> fields : parseQuestions(parsed.body())) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", fields.get("id"));
                        row.put("kp", fields.get("kp"));
                        row.put("courseId", courseId);
                        row.put("type", "short".equals(fields.get("type")) ? "short" : "choice");
                        row.put("stem", fields.get("stem"));
                        row.put("options", splitList(fields.get("options")));
                        row.put("answer", fields.get("answer"));
                        row.put("reference", fields.get("analysis"));
                        row.put("keywords", splitList(fields.get("keywords")));
                        row.put("score", intOf(fields.get("score")));
                        localQuestions.add(row);
                        allKps.add(fields.get("kp"));
                    }
                } catch (Exception e) {
                    log.warn("题库语料解析失败: {}: {}", f.getName(), e.getMessage());
                }
            }
        }
        try {
            MAPPER.writeValue(new File(props.getDataDir(), "question-bank.json"), localQuestions);
        } catch (Exception e) {
            log.warn("question-bank.json 写入失败: {}", e.getMessage());
        }
        // kp 词典：课程/题库/知识条目三处 kp 与别名汇总（去重），供 SlotExtractor 词典强约束
        List<String> distinct = allKps.stream().filter(StrUtil::isNotBlank).distinct().sorted().toList();
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("kps", distinct);
        dict.put("aliases", allAliases);
        dict.put("experienceLevels", List.of("零基础", "在校生", "1-3年经验", "3年以上经验"));
        try {
            MAPPER.writeValue(new File(props.getDataDir(), "kp-dictionary.json"), dict);
        } catch (Exception e) {
            log.warn("kp-dictionary.json 写入失败: {}", e.getMessage());
        }
        return localQuestions.size();
    }

    private void writeCourseOutline(String courseId, String name, String outline) {
        // 大纲由 MCP 端摄入写 MySQL；引擎侧仅记录日志校验存在性
        if (StrUtil.isBlank(outline)) {
            log.warn("课程 {} ({}) 缺少【课程大纲】节", courseId, name);
        }
    }

    /** 重建 ES 索引：删除 → 重建（mapping 由 EsMappingSupport 统一生成，中文分词自适应）→ 嵌入批量写入。文档以 Map 形式传入，embedding 缺失时现算 */
    @SuppressWarnings("unchecked")
    private void rebuildIndex(String index, List<Map<String, Object>> docs) {
        try {
            boolean exists = esClient.indices().exists(
                    co.elastic.clients.elasticsearch.indices.ExistsRequest.of(e -> e.index(index))).value();
            if (exists) {
                esClient.indices().delete(co.elastic.clients.elasticsearch.indices.DeleteIndexRequest.of(d -> d.index(index)));
            }
            String mappingJson = mappingSupport.knowledgeMapping(mappingSupport.vectorDims());
            esClient.indices().create(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.of(c -> c
                    .index(index)
                    .withJson(new ByteArrayInputStream(mappingJson.getBytes(StandardCharsets.UTF_8)))));
            var br = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
            for (Map<String, Object> doc : docs) {
                if (doc.get("embedding") == null) {
                    doc.put("embedding", embeddingService.embed(doc.get("title") + " " + doc.get("text")));
                }
                // 显式 _id=chunkId：与 KnowledgeIngestionService 的 upsert 路径幂等对齐，
                // 否则同内容两份（自动 _id + chunkId _id），ES 崩溃恢复后索引翻倍（测评演练实证）
                br.operations(op -> op.index(idx -> idx.index(index)
                        .id(String.valueOf(doc.get("chunkId")))
                        .document(doc)));
            }
            var result = esClient.bulk(br.build());
            log.info("ES 索引 {} 重建完成：{} 条文档（分词器 {}），errors={}",
                    index, docs.size(), mappingSupport.indexAnalyzer(), result.errors());
        } catch (Exception e) {
            throw new IllegalStateException("ES 索引 " + index + " 重建失败: " + e.getMessage(), e);
        }
    }

    private List<Map<String, String>> parseQuestions(String body) {
        Map<String, Map<String, String>> blocks = new LinkedHashMap<>();
        String currentId = null;
        for (String line : body.split("\n")) {
            Matcher header = Q_HEADER.matcher(line.trim());
            if (header.matches()) {
                currentId = header.group(1);
                blocks.put(currentId, new LinkedHashMap<>());
                continue;
            }
            if (currentId != null) {
                Matcher field = FIELD_LINE.matcher(line.trim());
                if (field.matches()) {
                    blocks.get(currentId).put(field.group(1), field.group(2).trim());
                }
            }
        }
        return blocks.entrySet().stream()
                .map(e -> {
                    Map<String, String> copy = new LinkedHashMap<>(e.getValue());
                    copy.put("id", e.getKey());
                    return copy;
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Parsed splitFrontmatter(String content) {
        Map<String, Object> frontmatter = new HashMap<>();
        String body = content;
        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int end = trimmed.indexOf("\n---", 3);
            if (end > 0) {
                String fmText = trimmed.substring(3, end).trim();
                body = trimmed.substring(trimmed.indexOf('\n', end + 1) + 1);
                org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                Object loaded = yaml.load(fmText);
                if (loaded instanceof Map) {
                    frontmatter = (Map<String, Object>) loaded;
                }
            }
        }
        Map<String, String> sections = new LinkedHashMap<>();
        String currentTitle = null;
        StringBuilder current = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("## ")) {
                if (currentTitle != null) {
                    sections.put(currentTitle, current.toString().trim());
                }
                currentTitle = line.substring(3).trim();
                current = new StringBuilder();
            } else if (currentTitle != null) {
                current.append(line).append('\n');
            }
        }
        if (currentTitle != null) {
            sections.put(currentTitle, current.toString().trim());
        }
        return new Parsed(frontmatter, sections, body);
    }

    private record Parsed(Map<String, Object> frontmatter, Map<String, String> sections, String body) {
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List) {
            return ((List<Object>) value).stream().map(String::valueOf).map(String::trim).toList();
        }
        return List.of();
    }

    private List<String> splitList(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        if (text.contains(") ")) {
            return java.util.Arrays.stream(text.split("(?<=\\S)\\s+(?=[A-D]\\))"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        return java.util.Arrays.stream(text.split("[,，、]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private int intOf(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
