package com.tutor.biz.service;

import com.tutor.biz.model.BizModels;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 订单服务（业务真相层）：预下单/支付/查询，状态机 PENDING_PAY → PAID。
 * 支付幂等：已 PAID 的订单重复支付直接返回原状态；
 * 预下单幂等：同用户同课程存在待支付订单时直接复用。
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final JdbcTemplate jdbc;
    private final CourseCatalogService courseCatalog;

    /** 题库权限数据源：用户是否已购买（PAID）该课程 */
    public boolean isPaid(Long userId, String courseId) {
        if (userId == null || courseId == null) {
            return false;
        }
        var rows = jdbc.queryForList(
                "SELECT status FROM order_info WHERE user_id=? AND course_id=? AND status='PAID' LIMIT 1",
                userId, courseId);
        return !rows.isEmpty();
    }

    public BizModels.OrderRecord createOrder(String courseId, Long userId) {
        // 幂等：同用户同课程已有待支付订单 → 直接返回
        var existing = jdbc.query(
                "SELECT order_id, course_id, course_name, price, status, pay_no FROM order_info WHERE user_id=? AND course_id=? AND status='PENDING_PAY'",
                (rs, i) -> toRecord(rs), userId, courseId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        var course = courseCatalog.detail(courseId);
        if (course == null) {
            throw new IllegalArgumentException("课程不存在: " + courseId);
        }
        String orderId = "o_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO order_info(order_id, course_id, course_name, price, user_id, status, created_at) VALUES(?,?,?,?,?,?,?)",
                orderId, courseId, course.name(), course.price(), userId, "PENDING_PAY", LocalDateTime.now());
        return new BizModels.OrderRecord(orderId, courseId, course.name(), course.price(), "PENDING_PAY", null);
    }

    public BizModels.OrderRecord payOrder(String orderId) {
        var order = require(orderId);
        if ("PAID".equals(order.status())) {
            return order; // 幂等
        }
        String payNo = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("UPDATE order_info SET status='PAID', pay_no=?, paid_at=? WHERE order_id=?",
                payNo, Timestamp.valueOf(LocalDateTime.now()), orderId);
        return new BizModels.OrderRecord(order.orderId(), order.courseId(), order.courseName(),
                order.price(), "PAID", payNo);
    }

    public BizModels.OrderRecord getOrder(String orderId) {
        return require(orderId);
    }

    private BizModels.OrderRecord require(String orderId) {
        var rows = jdbc.query("SELECT order_id, course_id, course_name, price, status, pay_no FROM order_info WHERE order_id=?",
                (rs, i) -> toRecord(rs), orderId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("订单不存在");
        }
        return rows.get(0);
    }

    private BizModels.OrderRecord toRecord(ResultSet rs) {
        try {
            return new BizModels.OrderRecord(rs.getString("order_id"), rs.getString("course_id"),
                    rs.getString("course_name"), rs.getInt("price"), rs.getString("status"),
                    rs.getString("pay_no") == null ? "" : rs.getString("pay_no"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
