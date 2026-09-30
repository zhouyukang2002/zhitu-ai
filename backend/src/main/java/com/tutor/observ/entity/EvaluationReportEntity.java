package com.tutor.observ.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 批量评估报告落库（评估闭环的"指标驱动迭代"存证） */
@Data
@TableName("evaluation_report")
public class EvaluationReportEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer traceCount;
    private Integer labeledCount;
    private BigDecimal avgScore;
    /** 指标均值 JSON（intentAccuracy/slotAccuracy/latencyScore/...） */
    private String metricAverages;
    /** 低分样本清单 JSON（<60 分回流清单） */
    private String lowScoreSamples;
    private Boolean includeJudge;
    private LocalDateTime createdAt;
}
