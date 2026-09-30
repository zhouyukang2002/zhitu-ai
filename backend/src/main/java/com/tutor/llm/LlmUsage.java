package com.tutor.llm;

/**
 * 一次 LLM 调用的观测数据（Token 消耗/耗时），由 LlmUsageHolder 在流终止后聚合。
 */
public record LlmUsage(String model, Integer promptTokens, Integer completionTokens, long latencyMs) {

    public int totalTokens() {
        return (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
    }
}
