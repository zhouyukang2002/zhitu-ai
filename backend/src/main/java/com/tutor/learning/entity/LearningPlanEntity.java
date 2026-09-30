package com.tutor.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 学习计划表 */
@Data
@TableName("learning_plan")
public class LearningPlanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    /** 路径数组 JSON：[{step, title, status}] */
    private String pathJson;

    private BigDecimal progress;

    private LocalDateTime createdAt;
}
