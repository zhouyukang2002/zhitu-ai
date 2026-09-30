package com.tutor.biz.service;

import com.tutor.biz.model.BizModels;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 工单服务：订单问题建单与查询（补齐"工单"业务能力） */
@Service
@RequiredArgsConstructor
public class TicketService {

    private final JdbcTemplate jdbc;

    public BizModels.TicketRecord createTicket(String orderId, Long userId, String issue) {
        String ticketId = "tk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO ticket_info(ticket_id, order_id, user_id, issue, status, created_at) VALUES(?,?,?,?,?,?)",
                ticketId, orderId, userId, issue, "OPEN", LocalDateTime.now());
        return new BizModels.TicketRecord(ticketId, orderId, issue, "OPEN");
    }

    public List<BizModels.TicketRecord> queryTickets(Long userId) {
        return jdbc.query("SELECT ticket_id, order_id, issue, status FROM ticket_info WHERE user_id=? ORDER BY created_at DESC",
                (rs, i) -> {
                    try {
                        return new BizModels.TicketRecord(rs.getString("ticket_id"), rs.getString("order_id"),
                                rs.getString("issue"), rs.getString("status"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, userId);
    }
}
