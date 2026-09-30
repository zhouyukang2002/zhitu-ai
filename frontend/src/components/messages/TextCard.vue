<template>
  <div class="text-card">
    <!-- 实时流式 Markdown 渲染：token 流入即时解析排版与代码高亮，附带流式光标 -->
    <div class="markdown-container">
      <MarkdownView :content="msg.content.text || ''" />
      <span v-if="msg.streaming" class="cursor" />
    </div>
    <div v-if="msg.failed" class="failed-tip">以上内容可能不完整，已中断</div>

    <!-- 底部互动与评估工具栏（流式结束后淡入呈现） -->
    <footer v-if="!msg.streaming" class="card-toolbar">
        <div class="toolbar-left">
          <!-- 知识溯源徽章 -->
          <span v-if="detectedCitation" class="citation-pill" title="企业知识库 RAG 权威检索依据">
            <svg viewBox="0 0 16 16" width="12" height="12" fill="none">
              <path d="M2.5 3A1.5 1.5 0 0 1 4 1.5h8A1.5 1.5 0 0 1 13.5 3v10A1.5 1.5 0 0 1 12 14.5H4A1.5 1.5 0 0 1 2.5 13V3Z" stroke="currentColor" stroke-width="1.2"/>
              <path d="M5 5h6M5 8h6M5 11h4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
            </svg>
            {{ detectedCitation }}
          </span>

          <!-- 研发/排障直达 Trace 徽标 -->
          <a
            v-if="effectiveTraceId"
            :href="'/admin.html?traceId=' + effectiveTraceId"
            target="_blank"
            class="trace-pill"
            :title="'在 APM 研发看板中查看本轮 Trace 瀑布流 (' + effectiveTraceId + ')'"
          >
            <svg viewBox="0 0 16 16" width="11" height="11" fill="none">
              <path d="M8.5 1.5 2 9.5h5.5l-1 5 6.5-8H7.5l1-5Z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>Trace</span>
          </a>
        </div>

        <div class="toolbar-actions">
          <!-- 一键复制 -->
          <button class="tool-btn" :class="{ copied }" title="复制内容" @click="copyText">
            <svg v-if="!copied" viewBox="0 0 16 16" width="13" height="13" fill="none">
              <rect x="5" y="5" width="8" height="8" rx="1.5" stroke="currentColor" stroke-width="1.2"/>
              <path d="M3 11V3.5A1.5 1.5 0 0 1 4.5 2H11" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
            </svg>
            <svg v-else viewBox="0 0 16 16" width="13" height="13" fill="none" class="check-icon">
              <path d="M3.5 8.5 6.5 11.5 12.5 4.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>{{ copied ? '已复制' : '复制' }}</span>
          </button>

          <!-- 👍 点赞 -->
          <button
            class="tool-btn"
            :class="{ active: userRating === 5 }"
            title="回答有帮助"
            @click="submitFeedback(5)"
          >
            <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
              <path d="M2 7.5h2v6H2zM4 8.5l3-6a1.5 1.5 0 0 1 2.8 1.1L9 6h4.5a1.5 1.5 0 0 1 1.5 1.5v1.2a1.5 1.5 0 0 1-.3.9l-2 3.5a1.5 1.5 0 0 1-1.3.9H4"
                stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>{{ userRating === 5 ? '已赞' : '赞' }}</span>
          </button>

          <!-- 👎 点踩 -->
          <button
            class="tool-btn"
            :class="{ active: userRating === 1 }"
            title="回答不满意"
            @click="submitFeedback(1)"
          >
            <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
              <path d="M2 8.5h2v-6H2zM4 7.5l3 6a1.5 1.5 0 0 0 2.8-1.1L9 10h4.5a1.5 1.5 0 0 0 1.5-1.5V7.3a1.5 1.5 0 0 0-.3-.9l-2-3.5a1.5 1.5 0 0 0-1.3-.9H4"
                stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>{{ userRating === 1 ? '已反馈' : '踩' }}</span>
          </button>
        </div>
      </footer>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import MarkdownView from '../common/MarkdownView.vue'

const props = defineProps({ msg: { type: Object, required: true } })

const copied = ref(false)
const userRating = ref(0)

// 严格探测引用的权威依据（必须来自明确的参考源前缀或字段，避免正文中的普通书名/电影名《》被误判）
const detectedCitation = computed(() => {
  // 1. 若后端返回了明确的 citations 字段
  if (props.msg?.content?.citations) {
    const c = String(props.msg.content.citations)
    return c.length > 18 ? c.slice(0, 18) + '…' : c
  }
  const text = props.msg?.content?.text || ''
  // 2. 只有明确带有“参考源 / 依据 / 知识库 / 出处”前缀时才识别
  const match = text.match(/(?:参考源|知识库依据|检索依据|文献出处)[：:\s*]*《?([^》\n\r*]{2,25})》?/)
  if (match && match[1]) {
    const raw = match[1].trim()
    return raw.length > 18 ? raw.slice(0, 18) + '…' : raw
  }
  return null
})

const effectiveTraceId = computed(() => {
  return props.msg?.traceId || props.msg?.content?.traceId || null
})

async function copyText() {
  const text = props.msg?.content?.text || ''
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    // 兼容回退
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}

async function submitFeedback(rating) {
  if (userRating.value === rating) return
  userRating.value = rating
  const traceId = props.msg?.traceId
  if (!traceId) return

  try {
    await fetch('/api/admin/feedback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        traceId,
        rating: String(rating),
        comment: rating === 5 ? 'helpful' : 'unhelpful'
      })
    })
  } catch (e) {
    // 忽略非致命网络抖动
  }
}
</script>

<style scoped>
.markdown-container {
  position: relative;
  font-size: 14.5px;
  line-height: 1.75;
  word-break: break-word;
}
.markdown-container :deep(.md) {
  padding: 2px 0;
}
.markdown-container :deep(p:last-child) {
  display: inline;
}
.cursor {
  display: inline-block;
  width: 6px;
  height: 15px;
  margin-left: 3px;
  vertical-align: -2px;
  background: var(--blue);
  border-radius: 2px;
  animation: blink 0.9s ease infinite;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.15; }
}
.failed-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--orange);
}

/* ── 底部互动工具栏 ── */
.card-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 0.5px solid rgba(0, 0, 0, 0.05);
  font-size: 12px;
  color: var(--text-3);
  user-select: none;
  animation: fadeIn 0.3s ease;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.citation-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: rgba(9, 105, 218, 0.06);
  color: var(--blue);
  border-radius: 12px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 260px;
}

.trace-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  font-weight: 500;
  color: #0284c7;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 12px;
  padding: 2px 7px;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.15s ease;
}

.trace-pill:hover {
  background: #e0f2fe;
  color: #0369a1;
  border-color: #7dd3fc;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--text-3);
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tool-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-1);
}

.tool-btn.active {
  background: rgba(9, 105, 218, 0.1);
  color: var(--blue);
  font-weight: 600;
}

.tool-btn.copied {
  color: var(--green);
  font-weight: 600;
}

.check-icon {
  color: var(--green);
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
