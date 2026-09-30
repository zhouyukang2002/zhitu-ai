package com.tutor.biz.config;

import com.tutor.biz.service.CourseCatalogService;
import com.tutor.biz.service.OrderService;
import com.tutor.biz.service.QuestionBankService;
import com.tutor.biz.service.TicketService;
import com.tutor.biz.tool.CourseTools;
import com.tutor.biz.tool.OrderTools;
import com.tutor.biz.tool.QuestionTools;
import com.tutor.biz.tool.TicketTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 装配：将四域工具（课程/题库/订单/工单）注册为标准 MCP 工具服务。
 * starter 自动在 /sse 与 /mcp/message 端点提供 JSON-RPC 2.0 协议，
 * 任何 MCP 宿主（Claude Desktop / MCP Inspector / 本项目 Agent）均可发现与调用。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider bizToolCallbackProvider(CourseCatalogService catalog,
                                                        QuestionBankService questionBank,
                                                        OrderService orderService,
                                                        TicketService ticketService) {
        // 方法级 @Tool 自动暴露为 MCP 工具（tools/list 含 JSON Schema，tools/call 标准执行）
        return MethodToolCallbackProvider.builder()
                .toolObjects(new CourseTools(catalog), new QuestionTools(questionBank),
                        new OrderTools(orderService), new TicketTools(ticketService))
                .build();
    }
}
