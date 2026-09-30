package com.tutor.client;

import java.util.List;

/**
 * 题库服务防腐层接口（与 CourseClient 同模式，保留接口缝）：
 * 现为本地 Mock 实现，将来接真实题库服务/MCP Server 零侵入替换。
 */
public interface QuestionBankClient {

    /** 按知识点与题型选题（type 为空取全部） */
    List<Question> byKp(String kp, String type);

    /** 按课程专属题库或知识点选题 */
    List<Question> byCourseOrKp(String courseId, String kp, String type);

    Question byId(String id);

    @lombok.Data
    class Question {
        private String id;
        private String kp;
        /** 所属课程绑定（可选，如 c010 为 SpringCloud 微服务架构实战课专属题） */
        private String courseId;
        /** choice / short */
        private String type;
        private String stem;
        private List<String> options;
        /** 选择题标准答案（A/B/C/D） */
        private String answer;
        /** 简答题参考答案 */
        private String reference;
        /** 简答题评分关键词（规则判分兜底用） */
        private List<String> keywords;
        /** 分值 */
        private int score;
    }
}
