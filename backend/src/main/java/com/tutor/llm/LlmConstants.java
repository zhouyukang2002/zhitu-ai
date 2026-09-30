package com.tutor.llm;

/**
 * 大模型与生成工程统一常量定义：
 * 消除散落在各智能体中的浮点数魔法值，统一收敛模型温度（Temperature）与采样行为语义。
 */
public final class LlmConstants {

    private LlmConstants() {}

    /**
     * 确定性/严格模式 (0.2)：
     * 适用于路由意图识别、槽位抽取、查询改写、客观题判分、Critic 审查与 LLM Judge。
     * 最大程度减少幻觉与随机发散，保障结构化输出与工程严谨性。
     */
    public static final double TEMP_DETERMINISTIC = 0.2;

    /**
     * 均衡模式 (0.7)：
     * 适用于概念原理讲解、学习路径规划。
     * 在严谨遵循知识库事实的基础上，保持解释语言的流畅度与通俗性。
     */
    public static final double TEMP_BALANCED = 0.7;

    /**
     * 发散/创造模式 (0.8)：
     * 适用于多轮开放式闲聊、关怀慰问、灵感发散。
     * 提高词汇多样性与语言温度，拟真自然人互动。
     */
    public static final double TEMP_CREATIVE = 0.8;
}
