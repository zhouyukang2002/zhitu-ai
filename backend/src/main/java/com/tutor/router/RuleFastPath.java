package com.tutor.router;

import com.tutor.client.CourseClient;
import com.tutor.client.CourseInfo;
import com.tutor.knowledge.SkillDictionary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则快路径（L0）：在 LLM 意图识别之前，用高精度强信号对"意图极其明确、零歧义"的请求直接路由，
 * 跳过一次 route-model 调用，降低 TTFB 与 Token 成本。
 *
 * <p><b>不误判是第一约束</b>：是否短路的裁决全部委托给 {@link IntentRuleTable#shortCircuit}，
 * 已经过「唯一强信号 / 无开放意图 / 无否定 / 无解题歧义」四重保守过滤；本类只额外负责槽位抽取。
 * 拿不准一律返回 {@link #EMPTY}，回到 LLM 主链路，快路径只做"减法"（省调用），永不改变拿不准请求的结果。
 *
 * <p>快路径产出的结果在 {@link IntentRouter} 中仍会经过槽位合并、状态矫正、环节裁决，
 * 与 LLM 路径走同一套安全网，不绕过任何下游校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleFastPath {

    private final SkillDictionary skillDictionary;
    private final CourseClient courseClient;

    /** 题数：阿拉伯数字或中文数字（一~五/两）+ 道/题 */
    private static final Pattern COUNT_PATTERN = Pattern.compile("([一二三四五两\\d])\\s*[道题]");
    /** 题型/难度/时间沿用 SlotExtractor 的受控词表，规则抽取天然合法 */
    private static final List<String> QUESTION_TYPES = List.of("选择题", "简答题", "填空题", "解答题", "判断题");
    private static final List<String> DIFFICULTIES = List.of("基础", "进阶", "冲刺");
    private static final List<String> TIME_RANGES = List.of("本周", "本月", "今天");

    public static final RuleHit EMPTY = new RuleHit(null, Slots.empty(), 0.0, false, List.of());

    /**
     * 评估一条消息是否可走规则快路径。
     * 返回 {@link RuleHit}：具备短路资格时 shortCircuit=true 且携带规则槽位；否则为 {@link #EMPTY}。
     */
    public RuleHit evaluate(String message) {
        Optional<Intent> opt = IntentRuleTable.shortCircuit(message);
        if (opt.isEmpty()) {
            return EMPTY;
        }
        Intent intent = opt.get();
        Slots slots = extractSlots(message);
        List<String> signals = IntentRuleTable.matchedStrongSignals(message);
        // 规则强信号的置信度：明确短语命中给高置信，但仍标注来源为规则，便于和 LLM 置信度区分统计
        return new RuleHit(intent, slots, 0.95, true, signals);
    }

    /**
     * 规则槽位抽取：只抽"规则能零歧义确定"的维度（固定词表 + 数字正则 + 词典最长匹配）。
     * 抽不到的维度留空，交给跨轮槽位合并沿用历史，绝不臆造。
     */
    private Slots extractSlots(String message) {
        String m = message == null ? "" : message;

        String questionType = firstContained(m, QUESTION_TYPES);
        String difficulty = firstContained(m, DIFFICULTIES);
        String timeRange = firstContained(m, TIME_RANGES);
        Integer count = extractCount(m);
        String kp = matchKnowledgePoint(m);
        String course = matchCourse(m);

        Slots raw = new Slots(kp, course, null, null, questionType, count, timeRange, difficulty);
        // 知识点/课程经词典归一与合法性校验（复用 SlotExtractor 同一套受控词表，保证规则槽位同样受词典约束）
        return normalizeByDictionary(raw);
    }

    private Slots normalizeByDictionary(Slots raw) {
        String kp = normalizeKp(raw.knowledgePoint());
        String course = normalizeCourse(raw.courseName());
        return new Slots(kp, course, null, raw.grade(), raw.questionType(),
                raw.questionCount(), raw.timeRange(), raw.difficulty());
    }

    /** 知识点归一：别名归一后必须确实落在受控词表内，否则丢弃（防止包含匹配误命中） */
    private String normalizeKp(String rawKp) {
        if (rawKp == null) {
            return null;
        }
        String normalized = skillDictionary.byNameOrAlias(rawKp);
        final String target = normalized;
        boolean inDict = skillDictionary.allNames().stream().anyMatch(k -> k.equals(target));
        return inDict ? normalized : null;
    }

    /** 课程名校验：必须确实存在于课程库，否则丢弃 */
    private String normalizeCourse(String rawCourse) {
        if (rawCourse == null) {
            return null;
        }
        final String target = rawCourse;
        boolean inDict = courseClient.all().stream().anyMatch(c -> c.getName().equals(target));
        return inDict ? rawCourse : null;
    }

    /** 知识点：优先使用词典的技术最长匹配与别名消解（技术实体优先于元属性词） */
    private String matchKnowledgePoint(String message) {
        String kp = skillDictionary.findKnowledgePointInText(message);
        if (kp != null) {
            return kp;
        }
        return skillDictionary.allNames().stream()
                .filter(message::contains)
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    /** 课程名：同样最长包含匹配 */
    private String matchCourse(String message) {
        List<CourseInfo> all = courseClient.all();
        if (all == null || all.isEmpty()) {
            return null;
        }
        return all.stream()
                .map(CourseInfo::getName)
                .filter(n -> n != null && message.contains(n))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private String firstContained(String message, List<String> vocab) {
        return vocab.stream().filter(message::contains).findFirst().orElse(null);
    }

    private Integer extractCount(String message) {
        Matcher matcher = COUNT_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        Integer n = chineseToInt(matcher.group(1));
        if (n == null) {
            return null;
        }
        // 与 SlotExtractor 的题数约束对齐：1~5，越界丢弃
        return (n >= 1 && n <= 5) ? n : null;
    }

    private Integer chineseToInt(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.matches("\\d")) {
            return Integer.parseInt(token);
        }
        return switch (token) {
            case "一" -> 1;
            case "两", "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            default -> null;
        };
    }

    /** 规则快路径结果 */
    public record RuleHit(Intent intent, Slots slots, double ruleConfidence,
                          boolean shortCircuit, List<String> signals) {
        public boolean present() {
            return intent != null;
        }

        public List<String> safeSignals() {
            return signals == null ? new ArrayList<>() : signals;
        }
    }
}
