package com.tutor.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 诊断报告表 */
@Data
@TableName("diagnosis_report")
public class DiagnosisReportEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    /** 薄弱点数组 JSON：[{knowledgePoint, score, confidence}] */
    private String weakPointsJson;

    private String summary;

    private LocalDateTime createdAt;
}
