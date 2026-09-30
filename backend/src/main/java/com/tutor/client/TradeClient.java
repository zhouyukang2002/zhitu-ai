package com.tutor.client;

import java.util.List;

/**
 * 交易服务防腐层接口（沿用原 tj-aigc 的 TradeClient 语义）：
 * 原实现是 Feign → tj-trade 微服务（prePlaceOrder）；抽离后本地实现持久化到 MySQL。
 * 预下单幂等键 orderId，支付确认由用户手动操作——大模型只触达"预下单"。
 */
public interface TradeClient {

    /** 预下单：返回订单（CREATED），不扣款 */
    com.tutor.trade.entity.CourseOrderEntity prePlaceOrder(Long userId, String conversationId, String courseId, String courseName, int price);
}
