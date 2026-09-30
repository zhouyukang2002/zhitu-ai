package com.tutor.llm;

import org.springframework.ai.chat.metadata.Usage;

import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 调用的 usage 聚合器：流式响应的 usage 在最后一个 chunk（OpenAI 兼容协议
 * stream_options.include_usage），逐 chunk 捕获、流终止后读取（线程安全）。
 */
public class LlmUsageHolder {

    private final AtomicReference<Usage> ref = new AtomicReference<>();
    private String model;
    private long latencyMs;

    public LlmUsageHolder(String model, long latencyMs) {
        this.model = model;
        this.latencyMs = latencyMs;
    }

    void capture(AtomicReference<Usage> captured, Usage usage) {
        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            captured.set(usage);
        }
    }

    void complete(String actualModel, Usage usage, long latencyMs) {
        if (usage != null) {
            ref.set(usage);
        }
        this.latencyMs = latencyMs;
        if (actualModel != null) {
            this.model = actualModel;
        }
    }

    public LlmUsage toUsage() {
        var u = ref.get();
        return new LlmUsage(model,
                u == null ? 0 : u.getPromptTokens(),
                u == null ? 0 : u.getCompletionTokens(),
                latencyMs);
    }
}
