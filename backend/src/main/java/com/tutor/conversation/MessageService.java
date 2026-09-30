package com.tutor.conversation;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.common.exception.BizException;
import com.tutor.conversation.entity.MessageEntity;
import com.tutor.conversation.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.tutor.observ.TraceContextHolder;

/**
 * 消息持久化：实时 SSE 发出的每种卡片同时落库（emit + 落库单点收口，杜绝双写重复）。
 * content 与实时卡片结构完全一致——历史消息接口直接可渲染（契约 1.3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MessageMapper messageMapper;

    /** 追加并落库一条消息（content 序列化为 JSON 存储），自动从 TraceContextHolder 读取本轮 traceId */
    public void append(String conversationId, String type, String role, Object content) {
        append(conversationId, type, role, content, TraceContextHolder.getTraceId());
    }

    /** 追加并落库一条消息，支持显式或隐式携带 traceId */
    public void append(String conversationId, String type, String role, Object content, String traceId) {
        MessageEntity entity = new MessageEntity();
        entity.setConversationId(conversationId);
        entity.setMsgId("m_" + IdUtil.fastSimpleUUID().substring(0, 12));
        entity.setType(type);
        entity.setRole(role);

        String tid = (traceId != null && !traceId.isBlank()) ? traceId : TraceContextHolder.getTraceId();
        Object payload = content;
        if (tid != null && !tid.isBlank() && "assistant".equalsIgnoreCase(role) && content instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            if (!copy.containsKey("traceId")) {
                copy.put("traceId", tid);
            }
            payload = copy;
        }

        entity.setContentJson(toJson(payload));
        entity.setTs(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(entity);
    }

    /** 历史消息（ts 升序），content 反序列化为 Map 与实时卡片一致 */
    public List<Map<String, Object>> listForApi(String conversationId) {
        requireExists(conversationId);
        List<MessageEntity> entities = messageMapper.selectList(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId)
                .orderByAsc(MessageEntity::getTs));
        return entities.stream().map(e -> Map.<String, Object>of(
                "id", e.getMsgId(),
                "type", e.getType(),
                "role", e.getRole(),
                "content", fromJson(e.getContentJson()),
                "ts", e.getTs()
        )).toList();
    }

    /** 会话内最近的纯文本消息（user/assistant，ts 倒序取 limit 条后正序返回），供查询改写/摘要作上下文 */
    public List<String> recentTexts(String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<MessageEntity> entities = messageMapper.selectList(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getType, "text")
                .orderByDesc(MessageEntity::getTs)
                .last("LIMIT " + Math.max(1, limit)));
        java.util.Collections.reverse(entities);
        return entities.stream().map(e -> {
            String role = "user".equals(e.getRole()) ? "学生" : "助教";
            return role + "：" + textOf(e.getContentJson());
        }).toList();
    }

    /** 会话内最近的用户纯文本消息（role=user，倒序取 limit 条后正序返回），避免助教长篇回复的词汇污染 */
    public List<String> recentUserTexts(String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<MessageEntity> entities = messageMapper.selectList(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getRole, "user")
                .eq(MessageEntity::getType, "text")
                .orderByDesc(MessageEntity::getTs)
                .last("LIMIT " + Math.max(1, limit)));
        java.util.Collections.reverse(entities);
        return entities.stream().map(e -> textOf(e.getContentJson())).filter(s -> s != null && !s.isBlank()).toList();
    }

    private String textOf(String contentJson) {
        try {
            Object parsed = MAPPER.readValue(contentJson, Map.class);
            return parsed instanceof Map<?, ?> m && m.get("text") != null ? String.valueOf(m.get("text")) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void requireExists(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw BizException.notFound("会话不存在");
        }
    }

    /**
     * 回写历史订单卡片状态（如 CREATED → PAID）：
     * 支付成功后刷新页面，订单卡片仍显示最新状态。
     */
    public void flipOrderStatus(String conversationId, String orderId, String status) {
        List<MessageEntity> orders = messageMapper.selectList(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getType, "course_order"));
        for (MessageEntity entity : orders) {
            try {
                Object parsed = fromJson(entity.getContentJson());
                if (!(parsed instanceof Map)) {
                    continue;
                }
                Map<String, Object> content = new java.util.LinkedHashMap<>((Map<String, Object>) parsed);
                if (orderId.equals(content.get("orderId"))) {
                    content.put("status", status);
                    entity.setContentJson(MAPPER.writeValueAsString(content));
                    messageMapper.updateById(entity);
                }
            } catch (Exception e) {
                log.warn("订单卡片回写失败: {}", e.getMessage());
            }
        }
    }

    private String toJson(Object content) {
        try {
            return MAPPER.writeValueAsString(content);
        } catch (Exception e) {
            log.error("消息 content 序列化失败", e);
            return "{}";
        }
    }

    private Object fromJson(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("text", "");
        }
    }
}
