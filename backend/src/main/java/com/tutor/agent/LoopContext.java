package com.tutor.agent;

import com.tutor.learning.CognitiveDiagnosis;
import com.tutor.learning.LearningState;
import com.tutor.router.RouteDecision;

import java.util.List;

/**
 * 一轮对话的共享状态（对齐 LangGraph typed state 思路）：
 * 路由与各智能体通过结构化字段传递上下文，禁止字符串拼接传递。
 */
public class LoopContext {

    private final String sessionId;
    private final Long userId;
    private final String message;
    private final RouteDecision decision;
    private final LearningState stateBefore;
    private final List<CognitiveDiagnosis.WeakPoint> latestWeakPoints;
    private final List<TraceStep> trace;
    private com.tutor.observ.TraceContext traceContext;

    public LoopContext(String sessionId, Long userId, String message, RouteDecision decision,
                       LearningState stateBefore, List<CognitiveDiagnosis.WeakPoint> latestWeakPoints) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.message = message;
        this.decision = decision;
        this.stateBefore = stateBefore;
        this.latestWeakPoints = latestWeakPoints;
        this.trace = TraceStep.newList();
    }

    public void addTrace(TraceStep step) {
        trace.add(step);
    }

    public com.tutor.observ.TraceContext getTraceContext() {
        return traceContext;
    }

    public void setTraceContext(com.tutor.observ.TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getMessage() {
        return message;
    }

    public RouteDecision getDecision() {
        return decision;
    }

    public LearningState getStateBefore() {
        return stateBefore;
    }

    public List<CognitiveDiagnosis.WeakPoint> getLatestWeakPoints() {
        return latestWeakPoints;
    }

    public List<TraceStep> getTrace() {
        return trace;
    }
}
