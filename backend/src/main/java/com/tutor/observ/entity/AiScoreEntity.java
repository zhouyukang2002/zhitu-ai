package com.tutor.observ.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 质量评分（用户反馈/LLM Judge/规则 三路） */
@Data
@TableName("ai_score")
public class AiScoreEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private String sessionId;
    private String source;
    private Integer rating;
    private String comment;
    private LocalDateTime createdAt;
}
