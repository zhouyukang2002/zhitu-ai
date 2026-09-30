<template>
  <div class="trace-root">
    <!-- ── 1. 顶部专业级浅色筛选器 ── -->
    <div class="filter-bar">
      <div class="filter-inputs">
        <div class="input-wrap search-main">
          <svg class="input-icon" viewBox="0 0 16 16" fill="none"><circle cx="6.5" cy="6.5" r="4" stroke="currentColor" stroke-width="1.4"/><path d="M10 10l3 3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>
          <input
            v-model="filter.query"
            class="filter-input query-input"
            placeholder="搜索消息内容 / Trace ID (t_...) / Session ID (s_...)"
            @keyup.enter="search"
          />
        </div>

        <select v-model="filter.intent" class="filter-select" @change="search">
          <option value="">全部意图 (ALL INTENTS)</option>
          <option value="TEACH">TEACH (知识讲解)</option>
          <option value="PLAN">PLAN (学习规划)</option>
          <option value="EXERCISE">EXERCISE (做题练习)</option>
          <option value="DIAGNOSE">DIAGNOSE (学情诊断)</option>
          <option value="REPORT">REPORT (学情报告)</option>
          <option value="COURSE_RECOMMEND">COURSE_RECOMMEND (课程推荐)</option>
          <option value="COURSE_BUY">COURSE_BUY (课程购买)</option>
          <option value="CHITCHAT">CHITCHAT (闲聊问答)</option>
          <option value="CLARIFY_NEEDED">CLARIFY_NEEDED (追问澄清)</option>
        </select>

        <div class="date-wrap">
          <svg class="date-icon date-icon-btn" viewBox="0 0 16 16" fill="none" @click="triggerPicker">
            <rect x="1.5" y="2.5" width="13" height="12" rx="2" stroke="currentColor" stroke-width="1.3"/>
            <path d="M1.5 6.5h13" stroke="currentColor" stroke-width="1.3"/>
            <path d="M5 1.5v2M11 1.5v2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
          </svg>
          <input
            v-model="filter.date"
            type="text"
            class="filter-input date-input"
            placeholder="YYYY-MM-DD"
            maxlength="10"
            @input="onDateInput"
            @keyup.enter="search"
          />
          <input
            ref="hiddenDate"
            type="date"
            class="date-hidden"
            :value="filter.date"
            @change="onPickerChange"
          />
        </div>

        <select v-model="filter.status" class="filter-select" @change="search">
          <option value="">全部状态 (ALL)</option>
          <option value="success">成功 (SUCCESS)</option>
          <option value="degraded">降级 (DEGRADED)</option>
          <option value="failed">失败 (FAILED)</option>
        </select>

        <button v-if="filter.date" class="btn-ghost btn-sm" @click="clearDate">✕ 清除日期</button>
      </div>

      <div class="filter-actions">
        <button class="btn-primary" :class="{ loading }" :disabled="loading" @click="search">
          <span v-if="loading" class="spin">⟳</span>
          {{ loading ? '查询中…' : '查询' }}
        </button>
        <div class="page-info">
          共 <b class="text-dark">{{ total }}</b> 条 · 第 <b>{{ page }}</b> / {{ totalPages }} 页
        </div>
      </div>
    </div>

    <!-- ── 2. Trace 数据表格 ── -->
    <div class="table-card">
      <div class="table-scroll">
        <table class="trace-table">
          <thead>
            <tr>
              <th class="col-msg">用户消息内容</th>
              <th class="col-intent">识别意图</th>
              <th class="col-source">路由来源</th>
              <th class="col-token">Token (In + Out)</th>
              <th class="col-cost">财务成本</th>
              <th class="col-latency">耗时</th>
              <th class="col-status">状态</th>
              <th class="col-label">标注</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(t, i) in items"
              :key="t.id"
              :class="{ selected: selectedId === t.id, even: i % 2 === 0 }"
              @click="selectTrace(t.id)"
            >
              <td class="col-msg cell-msg" :title="t.message">
                <div class="msg-text">{{ t.message || '—' }}</div>
                <div class="msg-sub-meta">
                  <code
                    class="trace-badge"
                    :class="{ copied: copiedTraceId === t.id }"
                    :title="copiedTraceId === t.id ? 'Trace ID 已复制！' : '点击复制 Trace ID'"
                    @click.stop="copyTraceId(t.id)"
                  >{{ copiedTraceId === t.id ? '✓ 已复制' : t.id }}</code>
                  <span class="session-badge" title="Session ID">{{ t.sessionId }}</span>
                </div>
              </td>
              <td class="col-intent">
                <span class="intent-tag">{{ t.intent || '—' }}</span>
              </td>
              <td class="col-source cell-mono dim">{{ t.routeSource || '—' }}</td>
              <td class="col-token cell-mono">{{ t.promptTokens || 0 }} + {{ t.completionTokens || 0 }}</td>
              <td class="col-cost cell-mono">
                <span class="cost-val">¥{{ t.cost ?? 0 }}</span>
              </td>
              <td class="col-latency cell-mono">
                <span :class="latencyClass(t.latencyMs)">{{ fmtLatency(t.latencyMs) }}</span>
              </td>
              <td class="col-status">
                <span class="status-pill" :class="t.status">{{ statusLabel(t.status) }}</span>
              </td>
              <td class="col-label">
                <span v-if="t.expectedIntent" class="label-dot labeled" title="已标注">●</span>
                <span v-else class="label-dot" title="未标注">○</span>
              </td>
            </tr>
            <tr v-if="!items.length && !loading">
              <td colspan="8" class="empty-row">
                <div class="empty-state">
                  <div>没有匹配的 Trace 记录</div>
                </div>
              </td>
            </tr>
            <tr v-if="loading && !items.length">
              <td colspan="8" class="empty-row">
                <div class="empty-state">
                  <span class="spin">⟳</span>
                  <div>正在加载链路数据…</div>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 分页控件 -->
      <div v-if="totalPages > 1" class="pagination">
        <button class="page-btn" :disabled="page <= 1" @click="goPage(page - 1)">‹ 上一页</button>
        <div class="page-nums">
          <button
            v-for="p in pageRange"
            :key="p"
            class="page-num"
            :class="{ active: p === page, ellipsis: p === '…' }"
            :disabled="p === '…'"
            @click="p !== '…' && goPage(p)"
          >{{ p }}</button>
        </div>
        <button class="page-btn" :disabled="page >= totalPages" @click="goPage(page + 1)">下一页 ›</button>
      </div>
    </div>

    <!-- ── 3. Trace 瀑布链路与详情抽屉 ── -->
    <transition name="detail-slide">
      <div v-if="detail" class="detail-card">
        <div class="detail-header">
          <div class="detail-header-left">
            <div class="detail-title">
              <span>Trace 瀑布时间线详情</span>
              <code
                class="detail-id clickable"
                :class="{ copied: copiedTraceId === detail.trace.id }"
                :title="copiedTraceId === detail.trace.id ? 'Trace ID 已复制！' : '点击复制 Trace ID'"
                @click="copyTraceId(detail.trace.id)"
              >{{ copiedTraceId === detail.trace.id ? '✓ ' + detail.trace.id + ' 已复制' : detail.trace.id }}</code>
              <button class="btn-ghost btn-xs copy-link-btn" @click="copyTraceLink" title="复制包含此 Trace 的 APM 直达排查链接">
                {{ copiedLink ? '✓ 链接已复制' : '🔗 复制链接' }}
              </button>
            </div>
            <div class="detail-meta-row">
              <span class="meta-item">会话: <code>{{ detail.trace.sessionId }}</code></span>
              <span class="meta-sep">·</span>
              <span class="meta-item">{{ fmtTime(detail.trace.createdAt) }}</span>
              <span class="meta-sep">·</span>
              <span class="meta-item">总耗时: <b class="text-dark">{{ detail.trace.latencyMs }}ms</b></span>
              <span class="meta-sep">·</span>
              <span class="meta-item">成本: <b class="text-purple">¥{{ detail.trace.cost ?? 0 }}</b></span>
            </div>
          </div>
          <button class="btn-ghost btn-sm" @click="closeDetail">收起 ✕</button>
        </div>

        <!-- 瀑布时间线 -->
        <div class="section-label">调用链路瀑布分解（Waterfall Breakdown）</div>
        <div class="waterfall">
          <div v-if="!detail.observations.length" class="wf-empty">无观测节点记录</div>
          <div
            v-for="o in detail.observations"
            :key="o.id"
            class="wf-row"
            :class="{ open: expanded === o.id }"
            @click="expanded = expanded === o.id ? null : o.id"
          >
            <div class="wf-left">
              <span class="wf-type-dot" :class="o.type === 'GENERATION' ? 'gen' : 'span'" />
              <span class="wf-name" :title="o.name">{{ o.name }}</span>
              <span class="wf-model dim">{{ o.tool || o.model || '' }}</span>
            </div>
            <div class="wf-center">
              <div class="wf-track">
                <div
                  class="wf-bar"
                  :class="[o.status !== 'success' ? o.status : (o.type === 'GENERATION' ? 'gen' : 'span')]"
                  :style="{ width: wfPct(o) + '%' }"
                />
              </div>
            </div>
            <div class="wf-right">
              <span class="wf-latency mono">{{ o.latencyMs }}ms</span>
              <span class="status-pill sm" :class="o.status">{{ statusLabel(o.status) }}</span>
            </div>
          </div>
        </div>

        <!-- 展开节点的输入输出 -->
        <div v-if="expandedObs" class="io-preview">
          <div class="io-head">
            <span class="io-title">节点输入/输出 · {{ expandedObs.name }}</span>
            <span class="io-sub mono">Token: in={{ expandedObs.promptTokens || 0 }}, out={{ expandedObs.completionTokens || 0 }}</span>
          </div>
          <div class="io-grid">
            <div class="io-box">
              <div class="io-label">Input / System Prompt</div>
              <pre class="io-content">{{ expandedObs.input || '—' }}</pre>
            </div>
            <div class="io-box">
              <div class="io-label">Output / Completion</div>
              <pre class="io-content">{{ expandedObs.output || '—' }}</pre>
            </div>
          </div>
        </div>

        <!-- 标注与评估修正 -->
        <div class="label-section">
          <div class="label-head">
            <span class="section-label">人工标注与预期评估（Labeling）</span>
            <span class="label-desc">标注真实预期意图与槽位，作为评估大盘的金标准</span>
          </div>
          <div class="label-form">
            <div class="form-row">
              <label>期望意图:</label>
              <select v-model="labelForm.expectedIntent" class="filter-select">
                <option value="">(未标注)</option>
                <option value="TEACH">TEACH (讲解)</option>
                <option value="PLAN">PLAN (规划)</option>
                <option value="EXERCISE">EXERCISE (练习)</option>
                <option value="CORRECT">CORRECT (批改)</option>
                <option value="DIAGNOSE">DIAGNOSE (诊断)</option>
                <option value="REPORT">REPORT (报告)</option>
                <option value="COURSE_RECOMMEND">COURSE_RECOMMEND (课程推荐)</option>
                <option value="COURSE_BUY">COURSE_BUY (课程购买)</option>
                <option value="CHITCHAT">CHITCHAT (闲聊)</option>
              </select>
              <button class="btn-primary btn-sm" :disabled="savingLabel" @click="saveLabelAction">
                {{ savingLabel ? '保存中…' : '保存标注' }}
              </button>
              <span v-if="labelMsg" class="label-msg text-green">{{ labelMsg }}</span>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { fetchTraces, fetchTrace, saveLabel } from '../adminApi'

const props = defineProps({
  dateFilter: { type: String, default: '' },
})

const filter = reactive({
  query: '',
  intent: '',
  date: props.dateFilter || '',
  status: '',
})

const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)

const selectedId = ref(null)
const detail = ref(null)
const expanded = ref(null)
const hiddenDate = ref(null)
const copiedLink = ref(false)
const copiedTraceId = ref(null)

const labelForm = reactive({ expectedIntent: '' })
const savingLabel = ref(false)
const labelMsg = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

const pageRange = computed(() => {
  const tp = totalPages.value
  const p = page.value
  if (tp <= 7) return Array.from({ length: tp }, (_, i) => i + 1)
  const set = new Set([1, tp, p, p - 1, p + 1].filter((n) => n >= 1 && n <= tp))
  const arr = Array.from(set).sort((a, b) => a - b)
  const res = []
  for (let i = 0; i < arr.length; i++) {
    if (i > 0 && arr[i] - arr[i - 1] > 1) res.push('…')
    res.push(arr[i])
  }
  return res
})

const expandedObs = computed(() => {
  if (!detail.value || !expanded.value) return null
  return detail.value.observations.find((o) => o.id === expanded.value)
})

function fmtLatency(ms) {
  return ms != null ? `${ms}ms` : '—'
}

function latencyClass(ms) {
  if (ms == null) return ''
  if (ms > 3000) return 'text-amber font-semibold'
  return 'text-dark'
}

function statusLabel(s) {
  const map = { success: 'SUCCESS', degraded: 'DEGRADED', failed: 'FAILED' }
  return map[s] || s || '—'
}

function fmtTime(s) {
  return s ? String(s).replace('T', ' ').slice(0, 19) : ''
}

function wfPct(o) {
  const max = detail.value?.trace?.latencyMs || 1
  return Math.min(100, Math.max(8, Math.round(((o.latencyMs || 0) / max) * 100)))
}

async function search() {
  page.value = 1
  await loadData()
}

async function goPage(p) {
  if (p < 1 || p > totalPages.value || p === page.value) return
  page.value = p
  await loadData()
}

async function loadData() {
  loading.value = true
  try {
    const res = await fetchTraces({
      query: filter.query || undefined,
      intent: filter.intent || undefined,
      status: filter.status || undefined,
      date: filter.date || undefined,
      page: page.value,
      size: pageSize,
    })
    items.value = res.items || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

async function selectTrace(id) {
  if (selectedId.value === id && detail.value) return
  selectedId.value = id
  detail.value = null
  expanded.value = null
  labelMsg.value = ''

  // 深度链接：自动将 traceId 同步至浏览器地址栏，随时复制链接分享排查
  const url = new URL(window.location)
  url.searchParams.set('traceId', id)
  window.history.replaceState(null, '', url)

  try {
    const d = await fetchTrace(id)
    detail.value = d
    labelForm.expectedIntent = d.trace?.expectedIntent || ''
    if (d.observations?.length) {
      expanded.value = d.observations[0].id
    }
  } catch (e) {
    console.error('获取 Trace 详情失败:', e)
  }
}

function closeDetail() {
  selectedId.value = null
  detail.value = null
  const url = new URL(window.location)
  url.searchParams.delete('traceId')
  window.history.replaceState(null, '', url)
}

function copyTraceLink() {
  if (!detail.value?.trace?.id) return
  const url = `${window.location.origin}/admin.html?traceId=${detail.value.trace.id}`
  navigator.clipboard?.writeText(url)
  copiedLink.value = true
  setTimeout(() => { copiedLink.value = false }, 2000)
}

function copyTraceId(id) {
  if (!id) return
  navigator.clipboard?.writeText(id)
  copiedTraceId.value = id
  setTimeout(() => {
    if (copiedTraceId.value === id) copiedTraceId.value = null
  }, 1800)
}

async function saveLabelAction() {
  if (!detail.value) return
  savingLabel.value = true
  labelMsg.value = ''
  try {
    await saveLabel(detail.value.trace.id, labelForm.expectedIntent)
    labelMsg.value = '✓ 标注已成功保存'
    if (detail.value.trace) detail.value.trace.expectedIntent = labelForm.expectedIntent
    const item = items.value.find((t) => t.id === detail.value.trace.id)
    if (item) item.expectedIntent = labelForm.expectedIntent
  } finally {
    savingLabel.value = false
  }
}

function triggerPicker() {
  hiddenDate.value?.showPicker?.()
}

function onPickerChange(e) {
  filter.date = e.target.value
  search()
}

function onDateInput(e) {
  if (e.target.value.length === 10) search()
}

function clearDate() {
  filter.date = ''
  search()
}

function applyDate() {
  if (props.dateFilter) {
    filter.date = props.dateFilter
    search()
  }
}

defineExpose({ applyDate })

onMounted(async () => {
  const urlParams = new URLSearchParams(window.location.search)
  const traceIdParam = urlParams.get('traceId')
  const sessionIdParam = urlParams.get('sessionId')
  const queryParam = urlParams.get('q') || urlParams.get('query')
  const intentParam = urlParams.get('intent')

  if (intentParam) filter.intent = intentParam
  if (props.dateFilter) filter.date = props.dateFilter

  if (traceIdParam) {
    filter.query = traceIdParam
    await loadData()
    await selectTrace(traceIdParam)
    return
  } else if (sessionIdParam) {
    filter.query = sessionIdParam
  } else if (queryParam) {
    filter.query = queryParam
  }

  loadData()
})
</script>

<style scoped>
.trace-root {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ── 1. 浅色筛选栏 ── */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 12px 18px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}

.filter-inputs {
  display: flex;
  align-items: center;
  gap: 12px;
}

.input-wrap, .date-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.input-icon, .date-icon {
  position: absolute;
  left: 10px;
  width: 14px;
  height: 14px;
  color: #94a3b8;
  pointer-events: none;
}

.date-icon-btn {
  pointer-events: auto;
  cursor: pointer;
}

.filter-input {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  padding: 6px 12px 6px 30px;
  color: #0f172a;
  font-size: 12.5px;
  outline: none;
  transition: border-color 0.15s ease;
}

.filter-input:focus {
  border-color: #0969da;
  background: #ffffff;
}

.date-hidden {
  position: absolute;
  opacity: 0;
  pointer-events: none;
  width: 0;
}

.filter-select {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  padding: 6px 12px;
  color: #0f172a;
  font-size: 12.5px;
  outline: none;
}

.filter-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.btn-primary {
  background: #0969da;
  color: #fff;
  border: none;
  padding: 6px 16px;
  border-radius: 7px;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-primary:hover { background: #054da7; }

.btn-ghost {
  background: transparent;
  border: 1px solid #cbd5e1;
  color: #64748b;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 11.5px;
  cursor: pointer;
}

.page-info {
  font-size: 12px;
  color: #64748b;
}

/* ── 2. 数据表格 ── */
.table-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}

.table-scroll {
  overflow-x: auto;
}

.trace-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  text-align: left;
}

.trace-table th {
  background: #f8fafc;
  padding: 12px 16px;
  color: #64748b;
  font-weight: 600;
  font-size: 11.5px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-bottom: 1px solid #e2e8f0;
}

.trace-table td {
  padding: 12px 16px;
  border-bottom: 1px solid #f1f5f9;
  color: #334155;
}

.trace-table tbody tr {
  cursor: pointer;
  transition: background 0.15s ease;
}

.trace-table tbody tr:hover {
  background: #f8fafc;
}

.trace-table tbody tr.selected {
  background: #eff6ff;
  border-left: 3px solid #0969da;
}

.query-input {
  min-width: 320px;
}

.cell-msg {
  max-width: 340px;
}

.msg-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 500;
  color: #0f172a;
}

.msg-sub-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}

.trace-badge {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 10.5px;
  background: #f0f9ff;
  color: #0284c7;
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid #bae6fd;
  cursor: pointer;
  transition: all 0.15s ease;
}

.trace-badge:hover {
  background: #e0f2fe;
  color: #0369a1;
}

.trace-badge.copied {
  background: #dcfce7 !important;
  color: #15803d !important;
  border-color: #86efac !important;
  font-weight: 600;
}

.session-badge {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 10px;
  color: #94a3b8;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.copy-link-btn {
  margin-left: 8px;
  padding: 2px 7px;
  font-size: 11px;
}

.intent-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
  background: #dbeafe;
  color: #1d4ed8;
}

.cost-val {
  color: #7c3aed;
  font-weight: 600;
}

.status-pill {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 4px;
}
.status-pill.success { background: #dcfce7; color: #15803d; }
.status-pill.degraded { background: #fef3c7; color: #b45309; }
.status-pill.failed { background: #fee2e2; color: #b91c1c; }

.label-dot { color: #cbd5e1; }
.label-dot.labeled { color: #15803d; }

.pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px;
  border-top: 1px solid #f1f5f9;
}

.page-btn, .page-num {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  color: #64748b;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}

.page-num.active {
  background: #0969da;
  border-color: #0969da;
  color: #fff;
  font-weight: 600;
}

/* ── 3. Trace 详情卡与瀑布图 ── */
.detail-card {
  background: #ffffff;
  border: 1px solid #bfdbfe;
  border-radius: 12px;
  padding: 22px 24px;
  box-shadow: 0 4px 20px rgba(9, 105, 218, 0.08);
  animation: fadeIn 0.25s ease;
}

.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
}

.detail-title {
  font-size: 15.5px;
  font-weight: 700;
  color: #0f172a;
  display: flex;
  align-items: center;
  gap: 10px;
}

.detail-id {
  font-size: 12px;
  color: #0969da;
  background: #dbeafe;
  padding: 2px 8px;
  border-radius: 4px;
  border: 1px solid #bfdbfe;
  transition: all 0.15s ease;
}

.detail-id.clickable {
  cursor: pointer;
}

.detail-id.clickable:hover {
  background: #bfdbfe;
  color: #1d4ed8;
}

.detail-id.copied {
  background: #dcfce7 !important;
  color: #15803d !important;
  border-color: #86efac !important;
  font-weight: 600;
}

.detail-meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
}

.section-label {
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 10px;
}

.waterfall {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 20px;
}

.wf-row {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  cursor: pointer;
  transition: all 0.15s ease;
}

.wf-row:hover, .wf-row.open {
  background: #eff6ff;
  border-color: #93c5fd;
}

.wf-left {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 260px;
  min-width: 0;
}

.wf-type-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.wf-type-dot.gen { background: #8b5cf6; }
.wf-type-dot.span { background: #0969da; }

.wf-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #1e293b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wf-center {
  flex: 1;
  padding: 0 16px;
}

.wf-track {
  height: 8px;
  background: #e2e8f0;
  border-radius: 4px;
  overflow: hidden;
}

.wf-bar {
  height: 100%;
  border-radius: 4px;
  background: #0969da;
}
.wf-bar.gen { background: linear-gradient(90deg, #8b5cf6, #ec4899); }

.wf-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.io-preview {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 20px;
}

.io-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-top: 10px;
}

.io-label {
  font-size: 11px;
  font-weight: 700;
  color: #64748b;
  margin-bottom: 4px;
}

.io-content {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  padding: 10px;
  border-radius: 6px;
  font-size: 11.5px;
  color: #334155;
  max-height: 220px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.label-section {
  padding-top: 14px;
  border-top: 1px solid #e2e8f0;
}

.label-form {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.form-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.mono { font-family: 'JetBrains Mono', Consolas, monospace; }
.text-dark { color: #0f172a; }
.text-green { color: #15803d; }
.text-amber { color: #b45309; }
.text-purple { color: #7c3aed; }
.text-blue { color: #0969da; }
.dim { color: #64748b; }
.spin { display: inline-block; animation: spin 1s infinite linear; }
@keyframes spin { 100% { transform: rotate(360deg); } }
</style>
