package com.tutor.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用户跨会话长期记忆实体（在 ES user_memory 索引中持久化与语义检索）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserMemoryItem {
    /** 记忆条目唯一 ID */
    private String memoryId;
    /** 用户 ID（多租户数据隔离） */
    private Long userId;
    /** 记忆类型：WEAK_POINT(技术薄弱点) / PREFERENCE(学习偏好) / GOAL(职业转型目标) / PROFILE(技术背景与岗位画像) */
    private String memoryType;
    /** 记忆陈述句（如：“非计算机专业零基础转Java后端，多线程并发与MySQL索引优化较薄弱”） */
    private String content;
    /** 产生该记忆的来源会话 ID */
    private String sourceSessionId;
    /** 记忆重要性权重（1~5） */
    private Integer importance;
    /** 创建时间毫秒时间戳 */
    private Long createdAt;
    /** 最近被召回访问时间 */
    private Long lastAccessedAt;
    /** 1024 维 Dense 语义向量 */
    private List<Float> embedding;
}
