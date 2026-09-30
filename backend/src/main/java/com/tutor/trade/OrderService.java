package com.tutor.trade;

import cn.hutool.core.util.IdUtil;
import com.tutor.common.exception.BizException;
import com.tutor.conversation.MessageService;
import com.tutor.trade.entity.CourseOrderEntity;
import com.tutor.trade.mapper.CourseOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单服务（限界上下文：交易）。
 * 幂等设计（话术锚点）：orderId 主键 + status 判重——重复支付返回原结果，
 * 状态不重复翻转；支付确认动作完全由用户手动操作，大模型只触达"预下单"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final CourseOrderMapper orderMapper;
    private final MessageService messageService;

    /** 预下单（幂等键 orderId 由服务端生成） */
    public CourseOrderEntity prePlace(Long userId, String conversationId, String courseId, String courseName, int price) {
        CourseOrderEntity order = new CourseOrderEntity();
        order.setOrderId("o_" + IdUtil.fastSimpleUUID().substring(0, 12));
        order.setUserId(userId);
        order.setConversationId(conversationId);
        order.setCourseId(courseId);
        order.setCourseName(courseName);
        order.setPrice(price);
        order.setStatus("CREATED");
        order.setCreatedAt(LocalDateTime.now());
        orderMapper.insert(order);
        return order;
    }

    public CourseOrderEntity require(String orderId) {
        CourseOrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.notFound("订单不存在");
        }
        return order;
    }

    /**
     * 支付确认（幂等 + 归属校验 + CAS 防重并发）：非本人订单统一 404；
     * 首支付翻转状态 + 回写历史订单卡片 + 追加 system 消息；已支付返回原结果（duplicated=true）。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayOutcome pay(String orderId, Long userId) {
        if (orderId == null || orderId.isBlank()) {
            throw BizException.badRequest("orderId 不能为空");
        }
        CourseOrderEntity order = require(orderId);
        if (!userId.equals(order.getUserId())) {
            throw BizException.notFound("订单不存在");
        }
        if ("PAID".equals(order.getStatus())) {
            return new PayOutcome(toContent(order), true);
        }

        // 乐观锁/CAS 原子状态翻转：仅当数据库当前仍为 CREATED 时执行更新，杜绝并发连击重放
        long paidAt = System.currentTimeMillis();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<CourseOrderEntity>()
                .eq(CourseOrderEntity::getOrderId, orderId)
                .eq(CourseOrderEntity::getStatus, "CREATED")
                .set(CourseOrderEntity::getStatus, "PAID")
                .set(CourseOrderEntity::getPaidAt, paidAt));

        if (rows == 0) {
            // 并发请求已先一步支付成功，直接以幂等方式返回
            CourseOrderEntity latest = require(orderId);
            return new PayOutcome(toContent(latest), true);
        }

        order.setStatus("PAID");
        order.setPaidAt(paidAt);

        // 历史消息里的订单卡片同步翻转状态（刷新后仍是已支付）
        messageService.flipOrderStatus(order.getConversationId(), orderId, "PAID");
        messageService.append(order.getConversationId(), "system", "assistant",
                Map.of("text", "课程「" + order.getCourseName() + "」购买成功"));
        return new PayOutcome(toContent(order), false);
    }

    /** 订单卡片 content（course_order 卡片契约） */
    public Map<String, Object> toContent(CourseOrderEntity order) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("orderId", order.getOrderId());
        content.put("courseId", order.getCourseId());
        content.put("courseName", order.getCourseName());
        content.put("price", order.getPrice());
        content.put("status", order.getStatus());
        if (order.getPaidAt() != null) {
            content.put("paidAt", order.getPaidAt());
        }
        return content;
    }

    public List<CourseOrderEntity> listPaidOrders(Long userId) {
        if (userId == null) return List.of();
        return orderMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CourseOrderEntity>()
                .eq(CourseOrderEntity::getUserId, userId)
                .eq(CourseOrderEntity::getStatus, "PAID"));
    }

    public record PayOutcome(Map<String, Object> order, boolean duplicated) {
    }
}
