package com.tutor.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 业务工具桥（三态路由）：Agent 的工具来源按 tutor.biz.mode 切换。
 * - mcp：经 MCP Client 发现的工具回调（协议真实走线，标准 JSON-RPC 2.0）
 * - local：进程内直调 @Tool 对象（降级路径，MCP 不可用时回退）
 *
 * 按智能体白名单过滤工具：每个 Agent 只暴露本域工具（工具最小暴露面，防业务幻觉）。
 * callTool：服务级调用入口——ExerciseAgent/CourseBuyAgent 通过此方法走 MCP 协议调用业务能力。
 */
@Slf4j
public class BizToolBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, List<String>> WHITELIST = Map.of(
            "TeachingAgent", List.of("searchCourses", "getCourseDetail"),
            "CourseRecommendAgent", List.of("searchCourses"),
            "ExerciseAgent", List.of("getQuestions"),
            "GradingAgent", List.of("getAnswerKey"),
            "CourseBuyAgent", List.of("createOrder", "payOrder", "getOrder", "createTicket", "queryTickets")
    );

    private final List<ToolCallback> mcpCallbacks;
    private final Object localTools;
    private final boolean mcpMode;

    public BizToolBridge(List<ToolCallback> mcpCallbacks, String mode, Object localTools) {
        if ("mcp".equals(mode) && mcpCallbacks != null && !mcpCallbacks.isEmpty()) {
            this.mcpCallbacks = mcpCallbacks;
            this.mcpMode = true;
            log.info("业务工具桥：MCP 模式，发现 {} 个工具回调", mcpCallbacks.size());
        } else {
            this.mcpCallbacks = List.of();
            this.mcpMode = false;
            log.info("业务工具桥：本地模式（mode={}, callbacks={}）", mode,
                    mcpCallbacks == null ? "null" : mcpCallbacks.size());
        }
        this.localTools = localTools;
    }

    public boolean isMcpMode() {
        return mcpMode;
    }

    /** FC 工具获取：按智能体白名单过滤（MCP 优先，local 降级） */
    public Object[] getTools(String agentName) {
        if (mcpMode) {
            List<String> whitelist = WHITELIST.getOrDefault(agentName, List.of());
            Object[] filtered = mcpCallbacks.stream()
                    .filter(cb -> whitelist.contains(cb.getToolDefinition().name()))
                    .toArray();
            if (filtered.length > 0) {
                log.debug("Agent[{}] 使用 MCP 工具 {} 个", agentName, filtered.length);
                return filtered;
            }
        }
        return new Object[]{localTools};
    }

    /**
     * 服务级调用：通过 MCP 协议执行业务工具（tools/call）。
     * ExerciseAgent 取题 / CourseBuyAgent 下单 / GradingAgent 取答案 都走此入口。
     * 返回 JSON 结果文本；MCP 不可用或调用失败返回 null（上层降级为本地路径）。
     */
    public String callTool(String toolName, String argsJson) {
        if (!mcpMode) {
            return null;
        }
        ToolCallback cb = mcpCallbacks.stream()
                .filter(c -> c.getToolDefinition().name().equals(toolName))
                .findFirst().orElse(null);
        if (cb == null) {
            log.debug("MCP 工具 {} 未发现", toolName);
            return null;
        }
        try {
            String result = cb.call(argsJson);
            log.debug("MCP callTool[{}] 成功", toolName);
            return result;
        } catch (Exception e) {
            log.warn("MCP callTool[{}] 失败: {}", toolName, e.getMessage());
            return null;
        }
    }
}
