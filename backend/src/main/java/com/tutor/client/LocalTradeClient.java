package com.tutor.client;

import com.tutor.trade.OrderService;
import com.tutor.trade.entity.CourseOrderEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 交易服务本地实现（防腐层）：预下单落到 tutor-engine 自己的 course_order 表，
 * 语义与原 Feign → tj-trade 的 prePlaceOrder 对齐。
 */
@Component
@RequiredArgsConstructor
public class LocalTradeClient implements TradeClient {

    private final OrderService orderService;

    @Override
    public CourseOrderEntity prePlaceOrder(Long userId, String conversationId,
                                           String courseId, String courseName, int price) {
        return orderService.prePlace(userId, conversationId, courseId, courseName, price);
    }
}
