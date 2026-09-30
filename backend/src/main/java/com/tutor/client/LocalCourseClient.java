package com.tutor.client;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * 课程服务本地 Mock 实现（防腐层，保留接口缝）。
 * 数据面：data/courses.json。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalCourseClient implements CourseClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties props;

    private List<CourseInfo> courses = List.of();

    @PostConstruct
    public void load() {
        reload();
    }

    /** 语料同步后调用：刷新课程列表缓存 */
    public synchronized void reload() {
        try {
            File file = new File(props.getDataDir(), "courses.json");
            if (file.exists()) {
                courses = MAPPER.readValue(FileUtil.readUtf8String(file), new TypeReference<>() {
                });
                log.info("课程数据刷新完成：{} 门课程", courses.size());
            }
        } catch (Exception e) {
            log.error("课程数据加载失败", e);
        }
    }

    @Override
    public List<CourseInfo> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return courses.stream()
                .filter(c -> c.getName().contains(keyword) || String.join(",", c.getTags()).contains(keyword))
                .toList();
    }

    @Override
    public List<CourseInfo> all() {
        return courses;
    }

    @Override
    public CourseInfo byId(String courseId) {
        return courses.stream()
                .filter(c -> c.getCourseId().equals(courseId))
                .findFirst().orElse(null);
    }
}
