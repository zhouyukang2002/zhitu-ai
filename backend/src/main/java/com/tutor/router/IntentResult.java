package com.tutor.router;

import java.util.List;

/**
 * 意图识别 Agent 的输出：意图 + 七维槽位 + 置信度 + 环节计划建议（对齐 diet-agent IntentResult 模式）。
 * stages 仅为开放意图（DIAGNOSE/PLAN）的提议值，封闭意图由编排层查表覆盖——LLM 提议，代码裁决。
 */
public record IntentResult(Intent intent, Slots slots, double confidence, List<Stage> stages) {

    public IntentResult(Intent intent, Slots slots, double confidence) {
        this(intent, slots, confidence, null);
    }

    public static IntentResult clarify(Slots slots) {
        return new IntentResult(Intent.CLARIFY_NEEDED, slots == null ? Slots.empty() : slots, 0.4, null);
    }

    public IntentResult safeSlots() {
        return new IntentResult(intent(), slots == null ? Slots.empty() : slots, confidence(), stages());
    }
}
