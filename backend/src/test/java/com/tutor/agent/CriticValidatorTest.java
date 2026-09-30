package com.tutor.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class CriticValidatorTest {

    private final CriticValidator validator = new CriticValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testValidateQuestion_Valid() throws Exception {
        String json = """
                {
                  "stem": "关于 Java 中的 HashMap，以下哪项描述是正确的？",
                  "options": ["A. 线程安全", "B. 基于数组+链表/红黑树实现", "C. 不允许 null 键", "D. 继承自 Vector"],
                  "answer": "B",
                  "analysis": "HashMap 是非线程安全的哈希表实现，基于数组+链表/红黑树。"
                }
                """;
        var res = validator.validateQuestion(mapper.readTree(json));
        Assertions.assertTrue(res.passed());
    }

    @Test
    void testValidateQuestion_InvalidAnswerKey() throws Exception {
        String json = """
                {
                  "stem": "关于 Java 中的 HashMap，以下哪项描述是正确的？",
                  "options": ["A. 选项一", "B. 选项二"],
                  "answer": "Z",
                  "analysis": "解析内容"
                }
                """;
        var res = validator.validateQuestion(mapper.readTree(json));
        Assertions.assertFalse(res.passed());
        Assertions.assertTrue(res.message().contains("不匹配"));
    }

    @Test
    void testValidateQuestion_DuplicateOptions() throws Exception {
        String json = """
                {
                  "stem": "测试重复选项题目",
                  "options": ["A. 重复项", "A. 重复项"],
                  "answer": "A",
                  "analysis": "解析内容"
                }
                """;
        var res = validator.validateQuestion(mapper.readTree(json));
        Assertions.assertFalse(res.passed());
        Assertions.assertTrue(res.message().contains("重复"));
    }

    @Test
    void testValidateExplanation_BalancedCode() {
        String text = """
                以下是并发单例模式代码：
                ```java
                public class Singleton {
                    private static volatile Singleton instance;
                    public static Singleton getInstance() {
                        if (instance == null) {
                            synchronized (Singleton.class) {
                                if (instance == null) instance = new Singleton();
                            }
                        }
                        return instance;
                    }
                }
                ```
                讲解结束。
                """;
        var res = validator.validateExplanation(text);
        Assertions.assertTrue(res.passed());
        Assertions.assertEquals(1, res.codeBlockCount());
    }

    @Test
    void testValidateExplanation_UnclosedMarkdown() {
        String text = """
                以下是代码：
                ```java
                public class Demo { }
                没有闭合标记
                """;
        var res = validator.validateExplanation(text);
        Assertions.assertFalse(res.passed());
        Assertions.assertTrue(res.issues().contains("Markdown 代码块未闭合"));
    }
}
