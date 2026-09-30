package com.tutor.biz.model;

import java.util.List;

/** 业务工具的输出模型（结构化，供 MCP 序列化） */
public final class BizModels {

    /** 课程摘要（searchCourses 输出） */
    public record CourseSummary(String courseId, String name, String grade, String teacher,
                                int hours, int price, String kp, List<String> tags) {
    }

    /** 课程详情（getCourseDetail 输出，含大纲全文） */
    public record CourseDetail(String courseId, String name, String grade, String teacher,
                               int hours, int price, String kp, String source, String outline) {
    }

    /** 题目（getQuestions 输出——默认不含答案字段，防泄题） */
    public record QuestionItem(String id, String kp, String type, String difficulty,
                               String stem, List<String> options, int score) {
    }

    /** 答案与解析（getAnswerKey 输出——仅批改链路调用） */
    public record AnswerKey(String id, String answer, String reference, List<String> keywords, int score) {
    }

    /** 订单（createOrder/payOrder/getOrder 输出） */
    public record OrderRecord(String orderId, String courseId, String courseName,
                              int price, String status, String payNo) {
    }

    /** 工单 */
    public record TicketRecord(String ticketId, String orderId, String issue, String status) {
    }
}
