package com.tutor.observ.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** AI 观测节点（SPAN/GENERATION，Langfuse 观测树模型） */
@Data
@TableName("ai_observation")
public class AiObservationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private Long parentId;
    private String type;
    private String name;
    private String tool;
    private String model;
    private String input;
    private String output;
    private Integer promptTokens;
    private Integer completionTokens;
    private BigDecimal cost;
    private Long latencyMs;
    private String status;
    private String error;
    private Long ts;
    private LocalDateTime createdAt;
}
