package com.tutor.conversation;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tutor.common.exception.BizException;
import com.tutor.conversation.entity.ConversationEntity;
import com.tutor.conversation.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话持久化服务（限界上下文：会话）。
 * 契约要点（对齐《后端开发注意事项》1.3）：
 * - 列表按 updatedAt 倒序；新建标题默认「新的对话」；
 * - 首条用户消息后取前 12 字生成标题（Mock 同款逻辑）；
 * - chat/stream 里 sessionId 不存在时自动建会话（幂等兜底）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    public static final String DEFAULT_TITLE = "新的对话";

    private final ConversationMapper conversationMapper;

    public ConversationEntity create(Long userId) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId("s_" + IdUtil.fastSimpleUUID().substring(0, 12));
        entity.setUserId(userId);
        entity.setTitle(DEFAULT_TITLE);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        conversationMapper.insert(entity);
        return entity;
    }

    /** chat/stream 用：会话不存在时以客户端传入的 id 自动创建（客户端持有会话 id） */
    public ConversationEntity getOrCreate(String sessionId, Long userId) {
        if (sessionId != null && !sessionId.isBlank()) {
            ConversationEntity existing = conversationMapper.selectById(sessionId);
            if (existing != null) {
                requireOwnership(existing, userId);
                return existing;
            }
            ConversationEntity entity = new ConversationEntity();
            entity.setId(sessionId);
            entity.setUserId(userId);
            entity.setTitle(DEFAULT_TITLE);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(entity.getCreatedAt());
            conversationMapper.insert(entity);
            return entity;
        }
        return create(userId);
    }

    /** 会话存在性 + 归属校验：非本人会话统一 404（不暴露"存在但不是你的"，防越权探测） */
    public ConversationEntity requireOwned(String sessionId, Long userId) {
        ConversationEntity entity = conversationMapper.selectById(sessionId);
        if (entity == null || !userId.equals(entity.getUserId())) {
            throw BizException.notFound("会话不存在");
        }
        return entity;
    }

    private void requireOwnership(ConversationEntity entity, Long userId) {
        if (!userId.equals(entity.getUserId())) {
            throw BizException.notFound("会话不存在");
        }
    }

    public ConversationEntity require(String sessionId) {
        ConversationEntity entity = conversationMapper.selectById(sessionId);
        if (entity == null) {
            throw BizException.notFound("会话不存在");
        }
        return entity;
    }

    public List<ConversationEntity> listByUser(Long userId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getUserId, userId)
                .orderByDesc(ConversationEntity::getUpdatedAt));
    }

    public void delete(String sessionId) {
        conversationMapper.deleteById(sessionId);
    }

    /** 首条用户消息生成标题（前 12 字），并刷新 updatedAt */
    public void touchAfterUserMessage(ConversationEntity session, String message) {
        if (DEFAULT_TITLE.equals(session.getTitle()) && message != null && !message.isBlank()) {
            String title = message.strip();
            session.setTitle(title.length() > 12 ? title.substring(0, 12) + "…" : title);
        }
        session.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(session);
    }
}
