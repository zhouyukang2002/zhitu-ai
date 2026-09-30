package com.tutor.agent;

import com.tutor.sse.SseSession;
import com.tutor.conversation.MessageService;
import com.tutor.agent.LoopContext;
import com.tutor.agent.TraceStep;
import com.tutor.client.CourseClient;
import com.tutor.client.CourseInfo;
import com.tutor.tool.BizToolBridge;
import com.tutor.client.TradeClient;
import com.tutor.trade.OrderService;
import com.tutor.trade.entity.CourseOrderEntity;
import com.tutor.common.constant.SseCardType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程购买智能体（交易域，旁路星型）：
 * 槽位精确命中 → 预下单（幂等 orderId）→ course_order 卡片；
 * 未命中 → 返回候选列表让用户点选确认，大模型不擅自选课下单。
 * MCP 模式：通过 BizToolBridge 协议调用业务系统；local 模式：进程内直调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseBuyAgent {

    public static final String AGENT = "课程购买智能体";

    private final CourseClient courseClient;
    private final TradeClient tradeClient;
    private final OrderService orderService;
    private final MessageService messageService;
    private final BizToolBridge bizToolBridge;

    public void buy(LoopContext ctx, SseSession sse, String courseName) {
        long start = System.currentTimeMillis();
        CourseInfo hit = courseName == null ? null
                : courseClient.search(courseName).stream().findFirst().orElse(null);
        if (hit != null) {
            CourseOrderEntity order = createOrderViaMcpOrLocal(ctx, hit);
            Map<String, Object> content = new LinkedHashMap<>(orderService.toContent(order));
            ctx.addTrace(new TraceStep(AGENT, "预下单（幂等 orderId，支付由用户确认）",
                    "交易工具 prePlaceOrder",
                    System.currentTimeMillis() - start, TraceStep.SUCCESS));
            messageService.append(ctx.getSessionId(), "course_order", "assistant", content);
            sse.card(SseCardType.COURSE_ORDER, content);
            return;
        }
        // 未精确命中：不擅自下单，返回候选列表让用户确认
        List<CourseInfo> candidates = courseClient.all().stream().limit(3).toList();
        String tip = "没有精确匹配到你要买的那门课。下面是与你的需求相关的课程，请点选具体课程后我再为你生成订单：";
        String streamed = StreamingSupport.streamText(tip, sse, 24, 18);
        messageService.append(ctx.getSessionId(), "text", "assistant", Map.of("text", streamed));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("source", "search");
        content.put("courses", candidates);
        ctx.addTrace(new TraceStep(AGENT, "课程名未精确命中，返回候选列表",
                "课程库检索",
                System.currentTimeMillis() - start, TraceStep.DEGRADED));
        messageService.append(ctx.getSessionId(), "course_list", "assistant", content);
        sse.card(SseCardType.COURSE_LIST, content);
    }

    private CourseOrderEntity createOrderViaMcpOrLocal(LoopContext ctx, CourseInfo hit) {
        // MCP 模式：协议调用业务系统（订单真相在 tutor-mcp-server 侧）
        String mcpResult = bizToolBridge.callTool("createOrder",
                "{\"courseId\":\"" + hit.getCourseId() + "\",\"userId\":" + ctx.getUserId() + "}");
        if (mcpResult != null) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mcpResult);
                var content = node.path("content");
                var e = new CourseOrderEntity();
                e.setOrderId(content.path("orderId").asText());
                e.setCourseId(content.path("courseId").asText(hit.getCourseId()));
                e.setCourseName(content.path("courseName").asText(hit.getName()));
                e.setPrice(content.path("price").asInt(hit.getPrice()));
                e.setStatus(content.path("status").asText("CREATED"));
                e.setUserId(ctx.getUserId());
                return e;
            } catch (Exception ex) {
                log.warn("MCP 订单解析失败，回退本地: {}", ex.getMessage());
            }
        }
        // local 降级：进程内直调
        return tradeClient.prePlaceOrder(
                ctx.getUserId(), ctx.getSessionId(), hit.getCourseId(), hit.getName(), hit.getPrice());
    }
}
