package com.tutor.router;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 七维槽位词典后置校验：非法值一律丢弃（防模型幻觉多写），
 * 题数范围钳制 [1,5]，合法值与 null 透传。
 */
class SlotsTest {

    private static final Map<String, List<String>> DICT = Map.of(
            "knowledgePoint", List.of("MySQL 索引", "集合框架", "多线程"),
            "courseName", List.of("Java 编程入门：从零基础到能写项目"),
            "grade", List.of("七上"),
            "questionType", List.of("选择题", "简答题"),
            "timeRange", List.of("本周", "本月"),
            "difficulty", List.of("基础", "进阶"));

    @Test
    void validated_词典内合法值全部保留() {
        Slots s = new Slots("MySQL 索引", "Java 编程入门：从零基础到能写项目", null,
                "七上", "选择题", 3, "本周", "进阶");
        Slots v = s.validated(DICT);
        Assertions.assertEquals("MySQL 索引", v.knowledgePoint());
        Assertions.assertEquals("Java 编程入门：从零基础到能写项目", v.courseName());
        Assertions.assertEquals("七上", v.grade());
        Assertions.assertEquals("选择题", v.questionType());
        Assertions.assertEquals(3, v.questionCount());
        Assertions.assertEquals("本周", v.timeRange());
        Assertions.assertEquals("进阶", v.difficulty());
    }

    @Test
    void validated_词典外非法值一律丢弃() {
        // 模型幻觉：kp/课程/题型不在词典 → 置 null
        Slots s = new Slots("区块链", "不存在的课程", null,
                "博三", "判断题", 2, "上周", "地狱");
        Slots v = s.validated(DICT);
        Assertions.assertNull(v.knowledgePoint());
        Assertions.assertNull(v.courseName());
        Assertions.assertNull(v.grade());
        Assertions.assertNull(v.questionType());
        Assertions.assertNull(v.timeRange());
        Assertions.assertNull(v.difficulty());
        Assertions.assertEquals(2, v.questionCount());
    }

    @Test
    void validated_题数越界钳制为null() {
        Assertions.assertNull(new Slots(null, null, null, null, null, 0, null, null).validated(DICT).questionCount());
        Assertions.assertNull(new Slots(null, null, null, null, null, 6, null, null).validated(DICT).questionCount());
        Assertions.assertNull(new Slots(null, null, null, null, null, -1, null, null).validated(DICT).questionCount());
        Assertions.assertEquals(1, new Slots(null, null, null, null, null, 1, null, null).validated(DICT).questionCount());
        Assertions.assertEquals(5, new Slots(null, null, null, null, null, 5, null, null).validated(DICT).questionCount());
    }

    @Test
    void validated_null值透传不抛异常() {
        Assertions.assertDoesNotThrow(() -> Slots.EMPTY.validated(DICT));
        Assertions.assertNull(Slots.EMPTY.validated(DICT).knowledgePoint());
    }

    @Test
    void withKnowledgePoint_仅替换目标槽位其余保持() {
        Slots s = new Slots(null, "Java 编程入门：从零基础到能写项目", "c001",
                null, "选择题", 5, null, null).withKnowledgePoint("多线程");
        Assertions.assertEquals("多线程", s.knowledgePoint());
        Assertions.assertEquals("c001", s.courseId());
        Assertions.assertEquals(5, s.questionCount());
    }
}
