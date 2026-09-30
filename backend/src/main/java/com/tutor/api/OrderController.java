package com.tutor.api;

import com.tutor.common.web.Result;
import com.tutor.common.context.UserContext;
import com.tutor.trade.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订单支付确认（旁路交易域 REST）：
 * 幂等键 orderId——重复支付返回原结果（duplicated=true），状态不重复翻转。
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/pay")
    public Result<Map<String, Object>> pay(@RequestBody Map<String, String> body) {
        OrderService.PayOutcome outcome = orderService.pay(body.get("orderId"), UserContext.getUser());
        return Result.ok(Map.of("order", outcome.order(), "duplicated", outcome.duplicated()));
    }
}
