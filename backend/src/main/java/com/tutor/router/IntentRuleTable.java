package com.tutor.router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 意图规则唯一事实源（决策表）：把原先散落在
 * ① {@link Intent} 枚举 patterns/KEYWORD_PRIORITY、② IntentRouter.fallback 关键词链、
 * ③ IntentRouter.revise 正则 三处的规则统一收口，避免加一个意图要改三处、口径漂移。
 *
 * <p>两套信号，严格区分精度档位，服务于不同环节：
 * <ul>
 *   <li><b>强信号 STRONG</b>：高精度、低歧义短语/组合，仅用于规则快路径短路（跳过 LLM）。
 *       设计铁律——宁可漏（漏了走 LLM，慢一点但正确），不可错（错了直接误路由，没有 LLM 纠偏机会）。</li>
 *   <li><b>弱信号 WEAK</b>：高召回宽匹配，仅用于 LLM 失败时的关键词兜底。弱信号用<b>有序条目</b>
 *       精确表达仲裁顺序（练习态调整词最优先，其次 报告&gt;购买&gt;推荐&gt;出题&gt;诊断&gt;规划&gt;讲解）。</li>
 * </ul>
 *
 * <p>快路径的"不误判"由否决逻辑保证，见 {@link #shortCircuit}：
 * 开放意图（诊断/规划，需要 LLM 裁决多环节编排）否决、多意图竞争否决、否定词否决、解题歧义否决。
 * 本类为纯静态逻辑、不依赖 Spring，便于 JUnit 直接断言。
 */
public final class IntentRuleTable {

    private IntentRuleTable() {
    }

    /** 封闭单环节意图（快路径只允许在这些意图里短路；DIAGNOSE/PLAN 为开放意图，绝不快路径） */
    private static final List<Intent> CLOSED_INTENTS = List.of(
            Intent.REPORT, Intent.COURSE_BUY, Intent.COURSE_RECOMMEND,
            Intent.EXERCISE, Intent.TEACH);

    /** 强信号表（快路径短路用）。每个模式都要求"组合/具体短语"，禁止用「怎么/？/课」这类单字泛词 */
    private static final Map<Intent, List<Pattern>> STRONG = new LinkedHashMap<>();

    /** 弱信号有序条目（LLM 失败兜底）：列表顺序即仲裁优先级，先命中先返回 */
    private static final List<WeakRule> WEAK_ORDERED = new ArrayList<>();

    /** 开放意图信号：一旦出现，说明可能是诊断/规划类复合请求，快路径必须放手交给 LLM 编排 */
    private static final List<Pattern> OPEN_INTENT_VETO = List.of(
            Pattern.compile("诊断|薄弱|摸底|错题|没掌握|哪里有问题|哪些没掌握|学情分析|学习问题|检测.{0,4}(学习|学情)|检查.{0,4}(学习|学情)"),
            Pattern.compile("学习计划|学习路径|学习路线|怎么学|先学什么|规划|转型|转行|路径安排|如何安排学"));

    /** 否定词：用户否定了动作本身（"不要出题"不能快路径到 EXERCISE） */
    private static final Pattern NEGATION = Pattern.compile("不要|不想|不用|别(给|出|推|讲)|无需|不需要|暂时不");

    /** 解题方向信号：用户在"给题求解答/探讨选项/作答求证"（TEACH），不是"向助教要题"（EXERCISE）——EXERCISE 快路径专属否决 */
    private static final Pattern SOLVE_QUESTION_VETO = Pattern.compile(
            "这道|这题|该题|怎么做|怎么解|怎么算|求解|解答一下|为什么选|答案是|选[A-Da-d]|应该选|我觉得选|我认为选|对吗|对不对|对么|已知.{0,20}求|帮我看.{0,3}题|错在哪");

    /** 购买中的"比较/挑选"信号：用户还在选（哪个更值得/A还是B），并非确定下单——COURSE_BUY 快路径否决 */
    private static final Pattern BUY_CHOICE_VETO = Pattern.compile(
            "买什么|买哪个|买哪种|怎么选|选哪个|选哪门|哪个|哪门|哪种|还是|对比|比较|值得买吗|好不好|纠结|区别");

    static {
        // ---------- 强信号 ----------
        // REPORT：要求"学情/学习/掌握/效果"语义共现，单独"报告"歧义大不入强信号
        STRONG.put(Intent.REPORT, List.of(
                Pattern.compile("学情报告|学习报告|学习效果报告|学习情况报告|效果报告"),
                Pattern.compile("学得怎么样|学的怎么样|学得如何|学的如何|掌握得如何|掌握的如何|掌握情况|掌握得怎么样"),
                Pattern.compile("学习效果|效果如何|最近学得|本周.{0,3}(报告|情况)|生成.{0,3}报告|看看.{0,3}报告")));

        // COURSE_BUY：必须"购买动词"与"课程指向"共现，光有"买"不算（"买什么资料"有歧义）
        STRONG.put(Intent.COURSE_BUY, List.of(
                Pattern.compile("(买|下单|订购|购买|入手|报名|开通).{0,8}(课|课程|教程|套餐|专栏|《)"),
                Pattern.compile("(课|课程|教程|套餐|《).{0,8}(买|下单|订购|购买|入手|报名|开通)"),
                Pattern.compile("想买课|要买课|帮我买|来一?份.{0,6}课")));

        // COURSE_RECOMMEND：必须与"课"共现，"推荐"单独太泛（"推荐学习方法"不是推课）
        STRONG.put(Intent.COURSE_RECOMMEND, List.of(
                Pattern.compile("推荐.{0,6}课|课.{0,6}推荐|课程推荐|选课建议|选课|报课|配套课程|配套课"),
                Pattern.compile("有什么课|有哪些课|适合.{0,8}课|推荐几门.{0,3}课|想学.{0,8}课|课程.{0,4}(建议|推荐)")));

        // EXERCISE：明确"向助教要题"的祈使方向
        STRONG.put(Intent.EXERCISE, List.of(
                Pattern.compile("出题|出几道|出[一二三四五两\\d]+道|来一组|来一套|来[一二三四五两\\d]+道|练几道|练一练|考考我"),
                Pattern.compile("给我.{0,4}(题|题目|练习)|做做题|做几道|练习题|再来一组|再出|换一批|换一组|一组题|一套题")));

        // TEACH：明确概念/原理提问；纯"怎么/如何/？"不入强信号（太泛，交 LLM）
        STRONG.put(Intent.TEACH, List.of(
                Pattern.compile("什么是|什么叫|是啥意思|讲讲|讲解|讲一下|讲一讲|给我讲|教我"),
                Pattern.compile("的原理|的概念|的用法|的作用|怎么理解|为什么会|的区别|区别是什么|是如何(工作|运行|实现)")));

        // ---------- 弱信号有序兜底（练习态调整词最灵敏，其后按成熟仲裁优先级） ----------
        addWeak(Intent.EXERCISE, "换一批|重做|再做|换一组|太简单|太难");
        addWeak(Intent.REPORT, "报告|学得怎么样|掌握得如何|效果如何");
        addWeak(Intent.COURSE_BUY, "买|下单|订购|购买");
        addWeak(Intent.COURSE_RECOMMEND, "推荐|配套课程|有什么课|报课|选课|课程");
        addWeak(Intent.EXERCISE, "再出|出题|练习|做题|题目|考考我");
        addWeak(Intent.DIAGNOSE, "诊断|薄弱|哪里有问题|哪些没掌握");
        addWeak(Intent.PLAN, "学习计划|学习路径|怎么学|先学什么");
        addWeak(Intent.TEACH, "讲解|讲讲|教我|什么是|怎么理解|为什么|怎么|如何|求|证明|计算|解|分析|已知|？|\\?");
        addWeak(Intent.CHITCHAT, "你是谁|你好|谢谢|hi|hello");
    }

    private static void addWeak(Intent intent, String regex) {
        WEAK_ORDERED.add(new WeakRule(intent, Pattern.compile(regex)));
    }

    // ==================== 强信号（快路径） ====================

    /** 命中强信号的封闭意图集合（可能多个，多个即代表有竞争歧义） */
    public static List<Intent> strongHits(String message) {
        String m = safe(message);
        List<Intent> hits = new ArrayList<>();
        for (Intent intent : CLOSED_INTENTS) {
            List<Pattern> patterns = STRONG.get(intent);
            if (patterns != null && patterns.stream().anyMatch(p -> p.matcher(m).find())) {
                hits.add(intent);
            }
        }
        return hits;
    }

    /** 是否含开放意图（诊断/规划）信号——快路径否决项 */
    public static boolean hasOpenIntentSignal(String message) {
        String m = safe(message);
        return OPEN_INTENT_VETO.stream().anyMatch(p -> p.matcher(m).find());
    }

    /** 是否含否定词 */
    public static boolean hasNegation(String message) {
        return NEGATION.matcher(safe(message)).find();
    }

    /** 是否含"给题求解"信号（EXERCISE 快路径否决，避免把解题误判成出题） */
    public static boolean hasSolveQuestionSignal(String message) {
        return SOLVE_QUESTION_VETO.matcher(safe(message)).find();
    }

    /**
     * 快路径短路裁决（核心防误判逻辑）。
     * 只有当：①恰好命中唯一一个封闭意图强信号；②无开放意图（诊断/规划）信号；
     * ③无否定词；④非 EXERCISE 时无"解题求解"信号；才允许短路。
     * 任一不满足返回 empty——放手交给 LLM，绝不猜。
     */
    public static Optional<Intent> shortCircuit(String message) {
        String m = safe(message);
        if (m.isBlank() || hasOpenIntentSignal(m) || hasNegation(m)) {
            return Optional.empty();
        }
        List<Intent> hits = strongHits(m);
        // 零命中或多意图竞争（歧义）都不短路
        if (hits.size() != 1) {
            return Optional.empty();
        }
        Intent intent = hits.get(0);
        // 出题快路径额外排除"给题求解"
        if (intent == Intent.EXERCISE && hasSolveQuestionSignal(m)) {
            return Optional.empty();
        }
        // 购买快路径排除"比较/挑选疑问"（"哪个更值得买/A还是B"本质是选型，应交 LLM）
        if (intent == Intent.COURSE_BUY && BUY_CHOICE_VETO.matcher(m).find()) {
            return Optional.empty();
        }
        return Optional.of(intent);
    }

    /** 返回命中的强信号原文（用于决策链路日志，解释"凭什么短路"） */
    public static List<String> matchedStrongSignals(String message) {
        String m = safe(message);
        List<String> signals = new ArrayList<>();
        for (Map.Entry<Intent, List<Pattern>> e : STRONG.entrySet()) {
            for (Pattern p : e.getValue()) {
                var matcher = p.matcher(m);
                if (matcher.find()) {
                    signals.add(e.getKey() + ":" + matcher.group());
                }
            }
        }
        return signals;
    }

    // ==================== 弱信号（LLM 失败兜底） ====================

    /**
     * 关键词兜底：按有序条目依次匹配返回首个命中（原 IntentRouter.fallback 的收口版本）。
     * 全部不命中时默认走 TEACH（最大化保证用户得到有效回答，沿用原口径）。
     */
    public static Intent weakFallback(String message) {
        String m = safe(message);
        if (m.isBlank()) {
            return Intent.CLARIFY_NEEDED;
        }
        for (WeakRule rule : WEAK_ORDERED) {
            if (rule.pattern().matcher(m).find()) {
                return rule.intent();
            }
        }
        return Intent.TEACH;
    }

    // ==================== 矫正规则（原 IntentRouter.revise 收口，统一口径） ====================

    /** 矫正零：服务/售后/政策类咨询走 TEACH（触发企业私有知识库），避免误判成推课 */
    public static boolean isServicePolicy(String message) {
        return safe(message).matches(".*(退款|退费|换课|调课|延期|规则|政策|协议|服务手册|权益|售后).*");
    }

    /** 矫正一：课程类按消费动词细分为购买/推荐 */
    public static Intent splitCourse(String message) {
        if (isChoosingCourses(message)) {
            return Intent.COURSE_RECOMMEND;
        }
        return safe(message).matches(".*(买|下单|订购|购买).*") ? Intent.COURSE_BUY : Intent.COURSE_RECOMMEND;
    }

    /**
     * 挑选/建议信号：用户在征求选课建议（买什么/该买什么/学什么课）而非确定下单。
     * 携带具体课程对象（书名号《》）时不适用——"买《XX》"是确定购买。
     */
    public static boolean isChoosingCourses(String message) {
        return safe(message).matches(".*(买什么|买哪个|该买什么|买哪种|怎么选|选哪个|选哪门|学什么课|有什么课|哪门课|哪门好).*")
                && !safe(message).matches(".*《.*》.*");
    }

    /** 矫正二：规划关键词（学习计划/怎么学等）强制转 PLAN */
    public static boolean isPlanKeyword(String message) {
        return safe(message).matches(".*(学习计划|学习路径|怎么学|先学什么).*");
    }

    /** 矫正三：练习态下的调整语义（再来一组/换一批等）转 EXERCISE */
    public static boolean isPracticeAdjust(String message) {
        return safe(message).matches(".*(再来|再一组|重做|再做|换一批|换一组).*");
    }

    /** 矫正三·b：探讨题目、作答或求证选项（转 TEACH 启发式答疑纠偏） */
    public static boolean isQuestionDiscussionOrAnswer(String message) {
        return SOLVE_QUESTION_VETO.matcher(safe(message)).find();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private record WeakRule(Intent intent, Pattern pattern) {
    }
}
