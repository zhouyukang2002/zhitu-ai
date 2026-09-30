package com.tutor.biz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务系统模拟器 · MCP 工具服务器。
 * 模拟原平台 tj-course/tj-trade/tj-exam 的对外能力，以标准 MCP 协议
 * （JSON-RPC 2.0 over SSE）向 AI 层与任何 MCP 宿主（如 Claude Desktop）暴露工具。
 */
@SpringBootApplication
public class BizServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BizServerApplication.class, args);
    }
}
