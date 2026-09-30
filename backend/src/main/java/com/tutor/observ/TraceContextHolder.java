package com.tutor.observ;

/**
 * 线程局部 Trace 上下文传递器：
 * 用于在单轮对话的请求主线程和 DAG 并行调度线程中传递当前 traceId，
 * 方便消息持久化（MessageService）自动关联 Trace 观测链路，实现历史消息一键直达 APM 看板。
 */
public final class TraceContextHolder {

    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();

    private TraceContextHolder() {}

    public static void setTraceId(String traceId) {
        TRACE_ID_HOLDER.set(traceId);
    }

    public static String getTraceId() {
        return TRACE_ID_HOLDER.get();
    }

    public static void clear() {
        TRACE_ID_HOLDER.remove();
    }
}
