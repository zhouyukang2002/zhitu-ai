package com.tutor.router;

import java.util.Locale;

/**
 * 流水线环节（执行计划的原子单元）。
 * 路由 LLM 对开放意图（DIAGNOSE/PLAN）提议 stages 数组，编排层裁决后按序分发到对应智能体；
 * 封闭意图由编排层查表固定为单环节。
 */
public enum Stage {

    DIAGNOSE, PLAN, TEACH, EXERCISE, REPORT, RECOMMEND, BUY, CLARIFY, CHITCHAT;

    /** 宽容解析：非法值返回 null（由编排层过滤，不抛异常） */
    public static Stage fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Stage.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
