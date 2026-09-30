package com.tutor.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话表（对应 Mock 的 sessions Map，落到 MySQL）
 */
@Data
@TableName("conversation")
public class ConversationEntity {

    /** 会话 id（业务主键，形如 s_xxx） */
    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;

    private String title;

    /** 状态机当前态（DIAGNOSED/PLANNED/LEARNING/PRACTICING/EVALUATED/REPLANNED），可空 */
    private String state;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
