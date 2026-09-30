package com.tutor.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程订单表（旁路交易域，不影响教学状态机）。
 * 状态机：CREATED（预下单）→ PAID（用户手动确认支付）。
 * 幂等：orderId 主键 + status 判重，重复支付返回原结果不重复翻转。
 */
@Data
@TableName("course_order")
public class CourseOrderEntity {

    /** 订单号（形如 o_xxx） */
    @TableId(type = IdType.INPUT)
    private String orderId;

    private Long userId;

    private String conversationId;

    private String courseId;

    private String courseName;

    private Integer price;

    /** CREATED / PAID */
    private String status;

    private Long paidAt;

    private LocalDateTime createdAt;
}
