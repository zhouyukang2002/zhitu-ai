package com.tutor.router;

import com.tutor.knowledge.SkillDictionary;
import com.tutor.learning.CognitiveDiagnosis;
import com.tutor.client.CourseClient;
import com.tutor.client.CourseInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 七维槽位词典服务（词典强约束的数据面）：
 * 1. slotOptions()：把七维合法候选值组装为 Map，注入意图识别提示词——LLM 只能从词典中选；
 * 2. validate(Slots)：解析后置校验——模型多写、写错的槽位一律丢弃（防槽位幻觉的第二道闸）；
 * 3. carryOverWeakPoint()：槽位缺失时携带最近诊断薄弱点（意图状态矫正的数据源）。
 *
 * 词典数据面：知识点/学段来自知识图谱，课程来自课程库，题型/时间/难度为固定词表。
 * 对齐 diet-agent 的 SlotOptionService（DB 驱动词典）模式，词典来源后续可迁移 DB。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotExtractor {

    /** 题型标准词表 */
    private static final List<String> QUESTION_TYPES = List.of("选择题", "简答题", "填空题", "解答题", "判断题");
    /** 时间范围标准词表 */
    private static final List<String> TIME_RANGES = List.of("本周", "本月", "今天");
    /** 难度标准词表 */
    private static final List<String> DIFFICULTIES = List.of("基础", "进阶", "冲刺");
    /** 题数约束 */
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 5;

    private final SkillDictionary skillDictionary;
    private final CourseClient courseClient;

    /** 七维合法候选值（注入意图识别提示词） */
    public Map<String, List<String>> slotOptions() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("knowledgePoint", skillDictionary.allNames());
        options.put("courseName", courseClient.all().stream().map(CourseInfo::getName).toList());
        options.put("grade", skillDictionary.experienceLevels());
        options.put("questionType", QUESTION_TYPES);
        options.put("questionCount", List.of("1 ~ " + MAX_COUNT + " 的整数"));
        options.put("timeRange", TIME_RANGES);
        options.put("difficulty", DIFFICULTIES);
        return options;
    }

    /** 解析后置校验：先归一再校验，非法槽位值一律丢弃（返回新 Slots） */
    public Slots validate(Slots slots) {
        if (slots == null) {
            return Slots.empty();
        }
        // 知识点按词典裸词/别名归一（如「python」归一为「Python 基础」，「集合」归一为「集合框架」）
        String rawKp = slots.knowledgePoint();
        String normalizedKp = (rawKp != null && !rawKp.isBlank()) ? skillDictionary.byNameOrAlias(rawKp) : null;
        Slots prepared = slots.withKnowledgePoint(normalizedKp);

        Map<String, List<String>> dicts = slotOptions();
        return prepared.validated(dicts);
    }

    /** 购买/讲解槽位为空时的兜底：携带最近诊断的第一薄弱点（意图状态矫正） */
    public String carryOverWeakPoint(List<CognitiveDiagnosis.WeakPoint> latestWeakPoints) {
        return latestWeakPoints.isEmpty() ? null : latestWeakPoints.get(0).knowledgePoint();
    }
}
