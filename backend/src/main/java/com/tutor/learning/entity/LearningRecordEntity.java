package com.tutor.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习记录（答题明细）：认知诊断的数据源。
 * 开发期 Mock 种子数据 + 批改后自动追加（形成真实数据闭环）。
 */
@Data
@TableName("learning_record")
public class LearningRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String knowledgePoint;

    private Boolean correct;

    /** 答题耗时（毫秒），0 表示未知 */
    private Integer timeMs;

    private Long ts;

    private LocalDateTime createdAt;
}
