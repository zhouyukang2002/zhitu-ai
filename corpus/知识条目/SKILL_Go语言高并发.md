---
kp: Go 协程与 Channel
category: SKILL
source: 《Go语言高并发实战指南》
aliases:
  goroutine: Go 协程与 Channel
  channel: Go 协程与 Channel
  gmp: Go 协程与 Channel
---
## Go 协程与 Channel
Go 语言原生支持轻量级线程 Goroutine（协程），由 Go 运行时调度器（GMP 模型：Goroutine, Machine, Processor）统一管理，每个协程初始仅占用 2KB 栈内存，可轻松创建百万并发。Channel 是 Go 语言的核心通信机制，遵循 CSP 并发模型——"不要通过共享内存来通信，而要通过通信来共享内存"。Channel 分为无缓冲和有缓冲两种，关闭后的 Channel 仍可读取剩余数据，向已关闭的 Channel 写入会导致 panic。
