package com.tutor.router;

/**
 * 意图枚举（L2 意图细分的输出空间）：
 * 学习域（教学闭环流水线）+ 交易域（旁路星型）+ 闲聊兜底。
 *
 * <p>注意：所有关键词/正则规则已统一收口到 {@link IntentRuleTable}（强信号快路径、弱信号兜底、
 * 状态矫正、仲裁优先级均在同一处维护），本枚举只表达意图分类本身与域归属，不再持有规则，避免口径漂移。
 */
public enum Intent {

    // ---- 学习域 ----
    DIAGNOSE("诊断学情"),
    PLAN("学习规划"),
    TEACH("知识讲解"),
    EXERCISE("练习出题"),
    REPORT("学情报告"),

    // ---- 交易域（旁路星型） ----
    COURSE_BUY("课程购买"),
    COURSE_RECOMMEND("课程推荐"),
    /** L2 意图识别 Agent 的输出类：课程类请求（推荐/购买共用），由编排层按消费动词细分 */
    COURSE("课程交易"),

    // ---- 澄清（信息不足需追问） ----
    CLARIFY_NEEDED("信息不足需澄清"),

    // ---- 闲聊兜底（不算业务意图） ----
    CHITCHAT("闲聊兜底");

    private final String desc;

    Intent(String desc) {
        this.desc = desc;
    }

    public String desc() {
        return desc;
    }

    public static boolean isTrade(Intent intent) {
        return intent == COURSE_BUY || intent == COURSE_RECOMMEND;
    }

    public static boolean isLearning(Intent intent) {
        return intent == DIAGNOSE || intent == PLAN || intent == TEACH
                || intent == EXERCISE || intent == REPORT;
    }
}
