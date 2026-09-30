package com.tutor.client;

import java.util.List;

/**
 * 课程服务防腐层接口（沿用原 tj-aigc 的 CourseClient 语义）：
 * 原实现是 Feign → tj-course 微服务；抽离后默认本地 Mock 实现。
 * 保留接口缝：将来接真实业务服务/MCP Server 时零侵入替换实现。
 */
public interface CourseClient {

    /** 按课程名精确/包含匹配（购买槽位词典的数据源） */
    List<CourseInfo> search(String keyword);

    /** 全量课程（推荐候选集） */
    List<CourseInfo> all();

    CourseInfo byId(String courseId);
}
