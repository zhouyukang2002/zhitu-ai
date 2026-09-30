package com.tutor.router;

import lombok.Builder;

import java.util.List;

/**
 * 路由决策（编排层产出，执行层流水线的输入）：
 * intent（业务意图 + 闲聊兜底）+ 来源 + 七维槽位（词典强约束结果）+ 本轮执行计划。
 * stages 已经过编排层裁决（允许集交集/状态机/保守默认），执行层直接按序分发。
 *
 * @param decisionTrace 决策演变链（P1）：按顺序记录"规则快路径/LLM原始/互证/槽位合并/矫正/校准/迟滞/降级"
 *                      每一步的关键变化，写入意图 JSONL，供 badcase 归因——能看清错误到底发生在哪一层。
 */
@Builder(toBuilder = true)
public record RouteDecision(
        Intent intent,
        /** RULE_FASTPATH / L2_LLM / KEYWORD_FALLBACK / LOW_CONFIDENCE_FALLBACK */
        String source,
        double confidence,
        /** 七维槽位 */
        Slots slots,
        /** 是否经历了降级（用于 trace 标注与评测统计） */
        boolean degraded,
        /** 本轮执行计划（已裁决，按序执行） */
        List<Stage> stages,
        /** 决策演变链（可观测/badcase 归因） */
        List<String> decisionTrace
) {

    public String kpSlot() {
        return slots == null ? null : slots.knowledgePoint();
    }

    public String courseName() {
        return slots == null ? null : slots.courseName();
    }
}
