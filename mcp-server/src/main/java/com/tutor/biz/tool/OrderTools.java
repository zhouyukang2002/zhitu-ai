package com.tutor.biz.tool;

import com.tutor.biz.model.BizModels;
import com.tutor.biz.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 交易域工具（模拟交易业务系统）：预下单/支付/查询。
 * 状态机 PENDING_PAY → PAID；支付幂等；支付确认由用户手动操作触发。
 * 消费者白名单：CourseBuyAgent。
 */
@Component
@RequiredArgsConstructor
public class OrderTools {

    private final OrderService orderService;

    @Tool(description = "课程预下单：为指定用户创建待支付订单。同用户同课程重复创建会复用已有订单（幂等）")
    public BizModels.OrderRecord createOrder(
            @ToolParam(description = "课程ID，如 course_detail_c002") String courseId,
            @ToolParam(description = "用户ID") Long userId) {
        return orderService.createOrder(courseId, userId);
    }

    @Tool(description = "订单支付确认：将待支付订单标记为已支付（幂等，已支付订单返回原状态）")
    public BizModels.OrderRecord payOrder(
            @ToolParam(description = "订单号") String orderId) {
        return orderService.payOrder(orderId);
    }

    @Tool(description = "查询订单当前状态与支付流水号")
    public BizModels.OrderRecord getOrder(
            @ToolParam(description = "订单号") String orderId) {
        return orderService.getOrder(orderId);
    }
}
