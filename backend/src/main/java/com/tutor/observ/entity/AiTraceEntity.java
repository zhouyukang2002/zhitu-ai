package com.tutor.observ.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** AI 调用链路（一轮对话 = 一条 Trace，含标注字段支撑评测闭环） */
@Data
@TableName("ai_trace")
public class AiTraceEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String sessionId;
    private Long userId;
    private String message;
    private String intent;
    private String routeSource;
    private String expectedIntent;
    private String expectedSlots;
    private String actualSlots;
    private Integer promptTokens;
    private Integer completionTokens;
    private BigDecimal cost;
    private Long latencyMs;
    private String status;
    private LocalDateTime createdAt;
}
