package com.tutor.exercise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批改结果表：exercise_id 唯一约束兜底幂等（Redis SETNX 为快速路径）
 */
@Data
@TableName("grade_result")
public class GradeResultEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String exerciseId;

    private String conversationId;

    private Integer score;

    private Integer totalScore;

    /** perQuestion 数组的 JSON */
    private String perQuestionJson;

    private String knowledgePoints;

    private LocalDateTime createdAt;
}
