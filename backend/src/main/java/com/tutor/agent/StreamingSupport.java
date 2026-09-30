package com.tutor.agent;

import com.tutor.sse.SseSession;
import reactor.core.publisher.Flux;

import java.util.Iterator;

/**
 * 流式输出辅助：LLM 增量逐帧下发 + 累积（中断/完成后由调用方持久化累积文本）。
 * reactor.core.publisher.Flux#toIterable 支持提前 break 取消上游订阅——
 * 中断检测点：每帧检查 SseSession 关闭标志，客户端 abort 立即停止消费（LLM 不白烧 token）。
 */
public final class StreamingSupport {

    private StreamingSupport() {
    }

    /** 消费 LLM 流：逐帧 delta 下发并累积；返回累积全文（含中断前的部分输出） */
    public static String stream(Flux<String> flux, SseSession sse) {
        StringBuilder acc = new StringBuilder();
        Iterator<String> it = flux.toIterable().iterator();
        try {
            while (it.hasNext()) {
                String chunk = it.next();
                acc.append(chunk);
                sse.delta(chunk);
                if (sse.isClosed()) {
                    break;
                }
            }
        } finally {
            // reactor 的迭代器实现了 Disposable：提前退出时取消上游订阅（停止 LLM 调用）
            if (it instanceof reactor.core.Disposable d) {
                d.dispose();
            }
        }
        return acc.toString();
    }

    /** 降级模式：本地文本模拟流式（打字机节奏），语义与真实流一致 */
    public static String streamText(String text, SseSession sse, int chunkSize, long pacingMs) {
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < text.length(); i += chunkSize) {
            if (sse.isClosed()) {
                break;
            }
            String chunk = text.substring(i, Math.min(text.length(), i + chunkSize));
            acc.append(chunk);
            sse.delta(chunk);
            try {
                Thread.sleep(pacingMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return acc.toString();
    }
}
