package com.tutor.api.dto;

import java.util.List;

/**
 * 会话列表/新建响应：{id, title, updatedAt}
 */
public record SessionVO(String id, String title, long updatedAt) {

    public static SessionVO of(com.tutor.conversation.entity.ConversationEntity e) {
        return new SessionVO(e.getId(), e.getTitle(), e.getUpdatedAt()
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    /**
     * 历史消息：统一消息结构 {id, type, role, content, ts}
     */
    public record MessageVO(String id, String type, String role, Object content, long ts) {
    }

}
