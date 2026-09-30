package com.tutor.biz.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.biz.model.BizModels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 课程目录服务（业务真相层，读 tutor_biz.course_info）：
 * 数据源为语料摄入（CorpusIngestService 写入），启动时全量载入内存，
 * 摄入完成后调用 reload() 刷新——50 门课程量级内存检索足够，省去每次查询的 IO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseCatalogService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${tutor.biz.data-dir:./data}")
    private String dataDir;

    private final JdbcTemplate jdbc;

    private List<CourseRow> courses = new ArrayList<>();

    /** course_info 行结构（roles/kps 为 JSON 数组字符串） */
    public record CourseRow(String courseId, String name, String track, String level, int price, int hours,
                            String teacher, List<String> roles, List<String> kps, String intro, String outline) {
    }

    @PostConstruct
    public void load() {
        try {
            courses = jdbc.query(
                    "SELECT course_id, name, track, level, price, hours, teacher, roles, kps, intro, outline FROM course_info",
                    (rs, i) -> new CourseRow(rs.getString("course_id"), rs.getString("name"),
                            rs.getString("track"), rs.getString("level"), rs.getInt("price"), rs.getInt("hours"),
                            rs.getString("teacher"), parseList(rs.getString("roles")), parseList(rs.getString("kps")),
                            StrUtil.nullToEmpty(rs.getString("intro")), StrUtil.nullToEmpty(rs.getString("outline"))));
            log.info("课程目录加载完成：{} 门课程（MySQL 数据面）", courses.size());
        } catch (Exception e) {
            log.warn("课程目录加载失败（表未建或为空）: {}", e.getMessage());
            courses = new ArrayList<>();
        }
    }

    /** 语料摄入后刷新内存数据面 */
    public synchronized void reload() {
        load();
    }

    public int count() {
        return courses.size();
    }

    /** 多条件检索：关键词（名称/介绍/知识点）+ 岗位或层级 + 价格上限 */
    public List<BizModels.CourseSummary> search(String keyword, String roleOrLevel, Integer maxPrice, Integer limit) {
        List<BizModels.CourseSummary> result = new ArrayList<>();
        for (CourseRow c : courses) {
            if (StrUtil.isNotBlank(keyword)
                    && !(c.name().contains(keyword) || c.intro().contains(keyword)
                        || c.kps().stream().anyMatch(k -> k.contains(keyword)))) {
                continue;
            }
            if (StrUtil.isNotBlank(roleOrLevel)
                    && !(c.level() != null && c.level().contains(roleOrLevel))
                    && c.roles().stream().noneMatch(r -> r.contains(roleOrLevel))
                    && c.track() != null && !c.track().contains(roleOrLevel)) {
                continue;
            }
            if (maxPrice != null && c.price() > maxPrice) {
                continue;
            }
            result.add(toSummary(c));
            if (limit != null && result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    public BizModels.CourseDetail detail(String courseId) {
        return courses.stream().filter(c -> c.courseId().equals(courseId)).findFirst()
                .map(c -> new BizModels.CourseDetail(c.courseId(), c.name(), c.level(), c.teacher(),
                        c.hours(), c.price(), String.join("/", c.kps()),
                        "《课程中心·" + c.name() + "》", c.outline().isEmpty() ? c.intro() : c.outline()))
                .orElse(null);
    }

    private BizModels.CourseSummary toSummary(CourseRow c) {
        List<String> tags = new ArrayList<>();
        if (c.track() != null) {
            tags.add(c.track());
        }
        if (c.level() != null) {
            tags.add(c.level());
        }
        tags.addAll(c.roles());
        return new BizModels.CourseSummary(c.courseId(), c.name(), c.level(), c.teacher(),
                c.hours(), c.price(), String.join("/", c.kps()), tags);
    }

    private List<String> parseList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
