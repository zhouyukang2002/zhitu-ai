package com.tutor.router;

import java.util.Map;

/**
 * 七维槽位（意图识别 Agent 抽取 + 词典后置校验，非法值一律丢弃）：
 * 1. knowledgePoint  知识点（知识图谱词典，含别名归一）
 * 2. course          课程（课程库词典）
 * 3. grade           学段（七上~九下）
 * 4. questionType    题型（选择/简答/填空/解答/判断）
 * 5. questionCount   题数（1~5）
 * 6. timeRange       时间范围（本周/本月/今天）
 * 7. difficulty      难度（基础/进阶/冲刺）
 */
public record Slots(String knowledgePoint, String courseName, String courseId, String grade,
                    String questionType, Integer questionCount, String timeRange, String difficulty) {

    public static final Slots EMPTY = new Slots(null, null, null, null, null, null, null, null);

    public static Slots empty() {
        return EMPTY;
    }

    public Slots withKnowledgePoint(String kp) {
        return new Slots(kp, courseName, courseId, grade, questionType, questionCount, timeRange, difficulty);
    }

    /** 后置校验：不在词典中的值一律置 null（模型多写、写错的槽位一律丢弃） */
    public Slots validated(Map<String, java.util.List<String>> dictionaries) {
        return new Slots(
                inDict(knowledgePoint, dictionaries.get("knowledgePoint")),
                inDict(courseName, dictionaries.get("courseName")),
                courseId,
                inDict(grade, dictionaries.get("grade")),
                inDict(questionType, dictionaries.get("questionType")),
                questionCount == null || questionCount >= 1 && questionCount <= 5 ? questionCount : null,
                inDict(timeRange, dictionaries.get("timeRange")),
                inDict(difficulty, dictionaries.get("difficulty")));
    }

    private static String inDict(String value, java.util.List<String> dict) {
        return value == null || dict == null || dict.contains(value) ? value : null;
    }
}
