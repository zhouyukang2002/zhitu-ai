package com.tutor.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息表：实时 SSE 发出的每种卡片/文本同时落库（历史完整），content 与实时卡片结构完全一致。
 */
@Data
@TableName("message")
public class MessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    /** 前端展示用的消息 id（形如 m_xxx） */
    private String msgId;

    /** user / assistant */
    private String role;

    /** 8 种消息类型：text/agent_trace/diagnosis/plan/exercise/grade/report/system */
    private String type;

    /** content 的 JSON 串（与实时卡片一致，如 text 的 content.text 是完整 Markdown 全文） */
    private String contentJson;

    /** 毫秒时间戳（前端排序/展示用） */
    private Long ts;

    private LocalDateTime createdAt;
}
