package com.tutor.biz.tool;

import com.tutor.biz.model.BizModels;
import com.tutor.biz.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工单域工具（模拟售后工单系统）：订单问题建单与查询 */
@Component
@RequiredArgsConstructor
public class TicketTools {

    private final com.tutor.biz.service.TicketService ticketService;

    @Tool(description = "为订单问题创建售后工单（如：课程无法播放、需要退款咨询）")
    public BizModels.TicketRecord createTicket(
            @ToolParam(description = "关联订单号，可为空", required = false) String orderId,
            @ToolParam(description = "用户ID") Long userId,
            @ToolParam(description = "问题描述") String issue) {
        return ticketService.createTicket(orderId, userId, issue);
    }

    @Tool(description = "查询指定用户的全部售后工单")
    public List<BizModels.TicketRecord> queryTickets(
            @ToolParam(description = "用户ID") Long userId) {
        return ticketService.queryTickets(userId);
    }
}
