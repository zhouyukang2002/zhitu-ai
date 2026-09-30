package com.tutor.pipeline;

/**
 * POST /api/chat/stream 请求体
 */
public record ChatRequest(String sessionId, Long userId, String message) {
}
