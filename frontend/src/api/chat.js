import { getAuthHeaders, getStoredUser } from '../utils/auth'

// SSE 流式对话：fetch + ReadableStream（支持 POST、自定义事件、AbortController）
// 事件契约：message{delta} / card{type, content} / done{} / error{message}
export async function streamChat({ sessionId, userId, message, onDelta, onCard, onDone, onError, signal }) {
  let resp
  const user = getStoredUser()
  const authHeaders = getAuthHeaders(userId || 1001)

  const headers = {
    'Content-Type': 'application/json',
    ...authHeaders,
  }
  try {
    resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers,
      body: JSON.stringify({ sessionId, userId: user?.userId || userId || 1001, message }),
      signal,
    })
  } catch (e) {
    if (signal?.aborted) return
    onError?.('网络异常，请检查服务是否可用')
    return
  }
  if (!resp.ok || !resp.body) {
    let msg = `服务异常（${resp.status}）`
    try { msg = (await resp.json()).message || msg } catch { /* keep default */ }
    onError?.(msg)
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const frames = buf.split('\n\n')
      buf = frames.pop() // 半帧留到下一轮
      for (const frame of frames) {
        const event = /^event: (.+)$/m.exec(frame)?.[1]
        const data = /^data: (.+)$/m.exec(frame)?.[1]
        if (!data) continue
        let payload
        try { payload = JSON.parse(data) } catch { continue }
        if (event === 'message') onDelta?.(payload.delta ?? '')
        else if (event === 'card') onCard?.(payload)
        else if (event === 'done') onDone?.(payload)
        else if (event === 'error') onError?.(payload.message || '服务异常')
      }
    }
  } catch (e) {
    if (!signal?.aborted) onError?.('连接中断，请重试')
  } finally {
    try { reader.releaseLock() } catch { /* ignore */ }
  }
}
