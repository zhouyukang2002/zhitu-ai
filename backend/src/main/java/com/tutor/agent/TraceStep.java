package com.tutor.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹步骤（agent_trace 卡片契约）：status ∈ success / failed / degraded。
 */
public record TraceStep(String agent, String action, String tool, long costMs, String status) {

    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String DEGRADED = "degraded";

    public static List<TraceStep> newList() {
        return new ArrayList<>();
    }
}
