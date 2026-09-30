package com.tutor.common.constant;

/**
 * SSE card 事件与持久化消息 type 的统一值域（wire 契约）。
 * 前端、e2e 断言、消息落库、状态机推导均依赖这些字符串值——
 * 集中定义为编译期常量，避免散落字面量改一处漏一处（e2e 卡片断言曾因此失配）。
 */
public final class SseCardType {

    public static final String DIAGNOSIS = "diagnosis";
    public static final String PLAN = "plan";
    public static final String EXERCISE = "exercise";
    public static final String GRADE = "grade";
    public static final String REPORT = "report";
    public static final String AGENT_TRACE = "agent_trace";
    public static final String COURSE_LIST = "course_list";
    public static final String COURSE_ORDER = "course_order";
    public static final String SYSTEM = "system";

    private SseCardType() {
    }
}
