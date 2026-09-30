package com.tutor.tool;

import com.tutor.client.CourseClient;
import com.tutor.client.CourseInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 课程查询工具（Function Calling 封装，原 tj-aigc CourseTools 的延续）：
 * 挂载到讲解/推荐智能体，LLM 可自动调用获取真实课程数据。
 * 工具白名单原则：只挂本领域工具，控制暴露面（防业务幻觉）。
 */
@Component
@RequiredArgsConstructor
public class CourseTools {

    private final CourseClient courseClient;

    @Tool(description = "根据课程名称关键词查询课程库，返回课程名称、价格、适用人群等真实业务数据；回答课程推荐/购买相关问题前必须先调用本工具")
    public List<CourseInfo> queryCourse(@ToolParam(description = "课程名称关键词，如：Java、Spring、MySQL、Python 或 Vue") String keyword) {
        return courseClient.search(keyword);
    }
}
