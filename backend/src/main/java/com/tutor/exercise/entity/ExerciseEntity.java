package com.tutor.exercise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 练习表：出题时登记（幂等校验存在性 + 重复批改数据源）
 */
@Data
@TableName("exercise")
public class ExerciseEntity {

    @TableId(type = IdType.INPUT)
    private String exerciseId;

    private String conversationId;

    /** 题目数组的 JSON（与 exercise 卡片 questions 一致） */
    private String questionsJson;

    /** 答案键 JSON（现场出题/私有题库：qid → answer/analysis/keywords/score），批改数据源 */
    private String answerKeysJson;

    /** 关联知识点（逗号分隔） */
    private String knowledgePoints;

    private LocalDateTime createdAt;
}
