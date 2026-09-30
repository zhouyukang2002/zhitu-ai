package com.tutor.learning;

/**
 * 学习进度状态机取值（严格对齐前端步骤条映射）：
 * DIAGNOSED / PLANNED / LEARNING / PRACTICING / EVALUATED / REPLANNED
 * REPLANNED 在前端回显到「规划」步骤（评估反馈回到规划的闭环体现）。
 */
public enum LearningState {

    DIAGNOSED, PLANNED, LEARNING, PRACTICING, EVALUATED, REPLANNED;

    /** 五步流水线的展示顺序（REPLANNED 由前端映射回规划步骤） */
    public static final String[] PIPELINE = {
            "DIAGNOSED", "PLANNED", "LEARNING", "PRACTICING", "EVALUATED"
    };
}
