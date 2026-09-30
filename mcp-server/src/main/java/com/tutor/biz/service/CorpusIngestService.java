package com.tutor.biz.service;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 语料摄入服务（Ingestion Pipeline，MD 即语料源）：
 * 扫描知识库目录，把课程/题库 Markdown 解析后写入 tutor_biz 业务库——
 * 课程介绍（引擎侧入 ES 语义索引）、大纲（本表）、题目（本表，答案权限隔离）三处数据同源。
 * 幂等：课程 upsert、题目按课程先删后插。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorpusIngestService {

    private static final Pattern Q_HEADER = Pattern.compile("^##\\s+(q_[A-Za-z0-9_]+)\\s*$");
    private static final Pattern FIELD_LINE = Pattern.compile("^-\\s+([A-Za-z]+)\\s*:\\s*(.*)$");

    @Value("${tutor.biz.corpus-dir:${TUTOR_CORPUS_DIR:../corpus}}")
    private String corpusDir;

    private final JdbcTemplate jdbc;

    private File getResolvedCorpusDir() {
        File f = new File(corpusDir);
        if (!f.exists()) {
            if (new File("./corpus").exists()) return new File("./corpus");
            if (new File("../corpus").exists()) return new File("../corpus");
        }
        return f;
    }

    public Map<String, Object> syncAll() {
        File root = getResolvedCorpusDir();
        File courseDir = new File(root, "课程");
        File bankDir = new File(root, "题库");
        int courses = 0;
        int questions = 0;
        List<String> errors = new ArrayList<>();
        if (courseDir.isDirectory()) {
            for (File f : listMd(courseDir)) {
                try {
                    courses += ingestCourse(f);
                } catch (Exception e) {
                    log.warn("课程语料解析失败: {}: {}", f.getName(), e.getMessage());
                    errors.add(f.getName() + ": " + e.getMessage());
                }
            }
        }
        if (bankDir.isDirectory()) {
            for (File f : listMd(bankDir)) {
                try {
                    questions += ingestQuestionBank(f);
                } catch (Exception e) {
                    log.warn("题库语料解析失败: {}: {}", f.getName(), e.getMessage());
                    errors.add(f.getName() + ": " + e.getMessage());
                }
            }
        }
        // 摄入后刷新内存数据面
        return Map.of("courses", courses, "questions", questions, "errors", errors);
    }

    private List<File> listMd(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        return files == null ? List.of() : List.of(files);
    }

    /** 解析课程 MD：frontmatter 结构化字段 + 课程介绍/大纲 两节，upsert 到 course_info */
    private int ingestCourse(File file) throws Exception {
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        Parsed parsed = splitFrontmatter(content);
        Map<String, Object> fm = parsed.frontmatter();
        String courseId = StrUtil.toStringOrNull(fm.get("courseId"));
        String name = StrUtil.toStringOrNull(fm.get("name"));
        if (courseId == null || name == null) {
            throw new IllegalArgumentException("缺少 courseId/name");
        }
        Map<String, String> sections = parsed.sections();
        String intro = sections.getOrDefault("课程介绍", "");
        String outline = sections.getOrDefault("课程大纲", "");
        if (intro.isBlank()) {
            throw new IllegalArgumentException("缺少【课程介绍】节");
        }
        List<String> kps = stringList(fm.get("kps"));
        List<String> roles = stringList(fm.get("roles"));
        jdbc.update("""
                INSERT INTO course_info(course_id, name, track, level, price, hours, teacher, roles, kps, intro, outline, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE name=VALUES(name), track=VALUES(track), level=VALUES(level),
                  price=VALUES(price), hours=VALUES(hours), roles=VALUES(roles), kps=VALUES(kps),
                  intro=VALUES(intro), outline=VALUES(outline), updated_at=VALUES(updated_at)
                """,
                courseId, name, StrUtil.toStringOrNull(fm.get("track")),
                StrUtil.toStringOrNull(fm.get("level")),
                intOf(fm.get("price")), intOf(fm.get("hours")),
                "企业教研团队", toJson(roles), toJson(kps), intro, outline,
                LocalDateTime.now());
        return 1;
    }

    /** 解析题库 MD：## q_xxx 题块 → 先删该课程旧题再批量插入（答案与解析留在本表，抽题接口不下发） */
    private int ingestQuestionBank(File file) throws Exception {
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        Parsed parsed = splitFrontmatter(content);
        String courseId = StrUtil.toStringOrNull(parsed.frontmatter().get("courseId"));
        if (courseId == null) {
            throw new IllegalArgumentException("缺少 courseId");
        }
        List<Map<String, Object>> rows = parseQuestions(parsed.body());
        if (rows.isEmpty()) {
            return 0;
        }
        jdbc.update("DELETE FROM question_bank WHERE course_id=?", courseId);
        for (Map<String, Object> q : rows) {
            jdbc.update("""
                    INSERT INTO question_bank(id, course_id, kp, type, difficulty, score, stem, options, answer, analysis, keywords)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    q.get("id"), courseId, q.get("kp"), q.get("type"), q.get("difficulty"), q.get("score"),
                    q.get("stem"), q.get("options"), q.get("answer"), q.get("analysis"), q.get("keywords"));
        }
        return rows.size();
    }

    /** 解析题目块：## q_xxx 标题 + "- field: value" 行 */
    private List<Map<String, Object>> parseQuestions(String body) {
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
        return blocks.entrySet().stream().map(e -> {
            Map<String, String> f = e.getValue();
            Map<String, Object> row = new HashMap<>();
            row.put("id", e.getKey());
            row.put("kp", f.get("kp"));
            row.put("type", f.get("type"));
            row.put("difficulty", f.get("difficulty"));
            row.put("score", intOf(f.get("score")));
            row.put("stem", f.get("stem"));
            row.put("options", splitList(f.get("options")) == null ? null : toJson(splitList(f.get("options"))));
            row.put("answer", f.get("answer"));
            row.put("analysis", f.get("analysis"));
            row.put("keywords", splitList(f.get("keywords")) == null ? null : toJson(splitList(f.get("keywords"))));
            return row;
        }).collect(Collectors.toList());
    }

    /** frontmatter 切分：首行 --- 到下一个 --- 之间为 YAML，其余为正文 */
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

    /** "A) xx B) yy" 或 "kw1, kw2" 拆分为列表 */
    private List<String> splitList(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        if (text.contains(") ")) {
            // 选项形态：按 A) B) C) 边界拆分
            return java.util.Arrays.stream(text.split("(?<=\\S)\\s+(?=[A-D]\\))"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        return java.util.Arrays.stream(text.split("[,，、]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String toJson(List<String> list) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
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
