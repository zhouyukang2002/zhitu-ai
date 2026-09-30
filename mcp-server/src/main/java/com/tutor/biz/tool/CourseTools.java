package com.tutor.biz.tool;

import com.tutor.biz.model.BizModels;
import com.tutor.biz.service.CourseCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 课程域工具（模拟 tj-course 对外能力）。
 * 消费者白名单：TeachingAgent（课程衔接推荐）、CourseRecommendAgent（候选生成）。
 */
@Component
@RequiredArgsConstructor
public class CourseTools {

    private final CourseCatalogService catalog;

    @Tool(description = "按条件检索课程：返回课程ID、名称、层级、讲师、课时、价格与目标岗位。回答课程推荐/查询类问题前必须先调用本工具")
    public List<BizModels.CourseSummary> searchCourses(
            @ToolParam(description = "关键词，如：Java、MySQL、数据分析、面试。可为空表示不限", required = false) String keyword,
            @ToolParam(description = "目标岗位或层级筛选，如：Java 后端工程师、数据分析师、入门。可为空", required = false) String roleOrLevel,
            @ToolParam(description = "价格上限（元）。可为空", required = false) Integer maxPrice,
            @ToolParam(description = "最多返回条数，默认 5", required = false) Integer limit) {
        return catalog.search(keyword, roleOrLevel, maxPrice, limit);
    }

    @Tool(description = "按课程ID查询课程完整详情：含大纲、层级、课时与定价")
    public BizModels.CourseDetail getCourseDetail(
            @ToolParam(description = "课程ID，如 c001") String courseId) {
        return catalog.detail(courseId);
    }
}
