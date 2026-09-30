<template>
  <div class="admin-shell">
    <!-- ── 顶级企业级浅色毛玻璃导航栏 ── -->
    <header class="admin-header">
      <div class="header-left">
        <div class="brand">
          <div class="logo-box">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none">
              <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <div class="brand-text">
            <div class="brand-title">
              <span class="name">TUTOR APM</span>
              <span class="badge-pro">ENTERPRISE</span>
            </div>
            <div class="sub">智能助教引擎 · 研发可观测与质量大盘</div>
          </div>
        </div>

        <nav class="nav-tabs">
          <button
            v-for="t in tabs"
            :key="t.key"
            class="tab-btn"
            :class="{ active: active === t.key }"
            @click="switchTab(t.key)"
          >
            <component :is="t.icon" class="tab-icon" />
            <span>{{ t.label }}</span>
            <span v-if="t.badge" class="tab-badge">{{ t.badge }}</span>
          </button>
        </nav>
      </div>

      <div class="header-right">
        <!-- 集群健康探针 -->
        <div class="cluster-status">
          <div class="status-chip" title="DeepSeek 大模型在线">
            <span class="pulse-dot green" />
            <span class="chip-text">DeepSeek V3</span>
          </div>
          <div class="status-chip" title="Elasticsearch 8.x 向量检索就绪">
            <span class="pulse-dot green" />
            <span class="chip-text">ES 8.15 RAG</span>
          </div>
        </div>

        <!-- 自动轮询开关 -->
        <button
          class="live-toggle"
          :class="{ active: isLive }"
          :title="isLive ? '自动刷新已开启 (10s)' : '点击开启自动刷新'"
          @click="toggleLive"
        >
          <span class="live-dot" :class="{ pulsing: isLive }" />
          <span>{{ isLive ? 'LIVE (10s)' : 'PAUSED' }}</span>
        </button>

        <!-- 一键返回学生端 -->
        <a class="portal-link" href="/" title="返回学生智能助教端">
          <span>学生交互端</span>
          <svg viewBox="0 0 16 16" width="12" height="12" fill="none">
            <path d="M6 3.5l4.5 4.5L6 12.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </a>
      </div>
    </header>

    <!-- ── 权限拦截界面（学生身份访问时展示） ── -->
    <div v-if="!isAdmin && authChecked" class="auth-block-card">
      <div class="auth-modal">
        <div class="auth-icon-box">
          <svg viewBox="0 0 24 24" width="28" height="28" fill="none">
            <rect x="3" y="11" width="18" height="11" rx="2" stroke="#cf222e" stroke-width="2"/>
            <path d="M7 11V7a5 5 0 0 1 10 0v4" stroke="#cf222e" stroke-width="2"/>
          </svg>
        </div>
        <h2>访问受限：需要管理员权限</h2>
        <p>研发看板（TUTOR APM）涉及底层算力成本、链路 Trace 与数据标注，仅对教研与研发管理员开放。当前登录账号为【普通学员视角】。</p>
        <div class="auth-btn-row">
          <button class="btn-primary" @click="handleSwitchToAdmin">
            👨‍💻 一键切换为管理员身份
          </button>
          <a href="/" class="btn-secondary">
            返回学员端
          </a>
        </div>
      </div>
    </div>

    <!-- ── 主视图容器（仅管理员可见） ── -->
    <main v-else class="admin-main">
      <div class="main-container">
        <!-- 指标总览 -->
        <div v-if="active === 'overview'" class="view-wrapper">
          <MetricsOverview ref="metricsOverview" @goto="onGoto" />
        </div>
        <!-- 链路追踪 -->
        <div v-else-if="active === 'traces'" class="view-wrapper">
          <TraceExplorer ref="tracePanel" :date-filter="traceDate" />
        </div>
        <!-- 批量评估 -->
        <div v-else-if="active === 'eval'" class="view-wrapper">
          <EvalPanel ref="evalPanel" />
        </div>
        <!-- 知识库管理 -->
        <div v-else-if="active === 'knowledge'" class="view-wrapper">
          <KnowledgePanel ref="knowledgePanel" />
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick, h } from 'vue'
import MetricsOverview from './components/MetricsOverview.vue'
import TraceExplorer from './components/TraceExplorer.vue'
import EvalPanel from './components/EvalPanel.vue'
import KnowledgePanel from './components/KnowledgePanel.vue'
import { useAuth } from '../stores/auth'

const { state: authState, isAdmin, initAuth, login } = useAuth()
const authChecked = ref(false)

async function handleSwitchToAdmin() {
  await login('admin', 'admin123')
  window.location.reload()
}

const OverviewIcon = () => h('svg', { viewBox: '0 0 16 16', width: 14, height: 14, fill: 'none' }, [
  h('rect', { x: 2, y: 2, width: 5, height: 5, rx: 1, stroke: 'currentColor', 'stroke-width': 1.4 }),
  h('rect', { x: 9, y: 2, width: 5, height: 5, rx: 1, stroke: 'currentColor', 'stroke-width': 1.4 }),
  h('rect', { x: 2, y: 9, width: 5, height: 5, rx: 1, stroke: 'currentColor', 'stroke-width': 1.4 }),
  h('rect', { x: 9, y: 9, width: 5, height: 5, rx: 1, stroke: 'currentColor', 'stroke-width': 1.4 }),
])

const TraceIcon = () => h('svg', { viewBox: '0 0 16 16', width: 14, height: 14, fill: 'none' }, [
  h('path', { d: 'M2 4h12M2 8h8M2 12h10', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round' }),
])

const EvalIcon = () => h('svg', { viewBox: '0 0 16 16', width: 14, height: 14, fill: 'none' }, [
  h('circle', { cx: 8, cy: 8, r: 6, stroke: 'currentColor', 'stroke-width': 1.4 }),
  h('path', { d: 'M5.5 8l2 2 3.5-4', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }),
])

const KnowledgeIcon = () => h('svg', { viewBox: '0 0 16 16', width: 14, height: 14, fill: 'none' }, [
  h('path', { d: 'M3 2.5h7.5a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H3V2.5z', stroke: 'currentColor', 'stroke-width': 1.4 }),
  h('path', { d: 'M3 5.5h7M3 8.5h5', stroke: 'currentColor', 'stroke-width': 1.4, 'stroke-linecap': 'round' }),
])

const tabs = [
  { key: 'overview', label: '指标总览', icon: OverviewIcon },
  { key: 'traces', label: '链路追踪', icon: TraceIcon },
  { key: 'eval', label: '批量评估', icon: EvalIcon, badge: '三路加权' },
  { key: 'knowledge', label: '知识库管理', icon: KnowledgeIcon, badge: 'ES 8.x' },
]

const active = ref('overview')
const evalPanel = ref(null)
const tracePanel = ref(null)
const metricsOverview = ref(null)
const knowledgePanel = ref(null)
const traceDate = ref('')
const isLive = ref(true)
let pollTimer = null

function switchTab(key) {
  active.value = key
  history.replaceState(null, '', key === 'overview' ? location.pathname : '#' + key)
}

function applyHash() {
  const h = location.hash.replace('#', '')
  if (tabs.some((t) => t.key === h)) active.value = h
}

async function onGoto(payload) {
  traceDate.value = payload && payload.date ? payload.date : ''
  active.value = 'traces'
  history.replaceState(null, '', '#traces')
  await nextTick()
  tracePanel.value?.applyDate?.()
}

function toggleLive() {
  isLive.value = !isLive.value
  if (isLive.value) {
    startPolling()
  } else {
    stopPolling()
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => {
    if (active.value === 'overview') {
      metricsOverview.value?.refresh?.()
    }
  }, 10000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(async () => {
  await initAuth()
  authChecked.value = true
  if (isAdmin.value) {
    applyHash()
    window.addEventListener('hashchange', applyHash)
    if (isLive.value) startPolling()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', applyHash)
  stopPolling()
})
</script>

<style scoped>
.admin-shell {
  min-height: 100vh;
  background: #f8fafc;
  color: #0f172a;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
}

/* ── 权限拦截卡片 ── */
.auth-block-card {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: calc(100vh - 64px);
  padding: 24px;
}
.auth-modal {
  background: #ffffff;
  border: 1px solid rgba(207, 34, 46, 0.2);
  border-radius: 16px;
  padding: 40px 32px;
  max-width: 480px;
  text-align: center;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.08);
}
.auth-icon-box {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: rgba(207, 34, 46, 0.08);
  display: grid;
  place-items: center;
  margin: 0 auto 16px;
}
.auth-modal h2 {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 10px;
}
.auth-modal p {
  font-size: 13.5px;
  color: #64748b;
  line-height: 1.6;
  margin: 0 0 24px;
}
.auth-btn-row {
  display: flex;
  gap: 12px;
  justify-content: center;
}
.btn-primary {
  background: #0969da;
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 10px 18px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;
}
.btn-primary:hover {
  background: #054da7;
}
.btn-secondary {
  display: inline-flex;
  align-items: center;
  background: #f1f5f9;
  color: #475569;
  text-decoration: none;
  border-radius: 8px;
  padding: 10px 18px;
  font-size: 13px;
  font-weight: 600;
  transition: background 0.15s ease;
}
.btn-secondary:hover {
  background: #e2e8f0;
  color: #0f172a;
}

/* ── 浅色高级毛玻璃顶栏 ── */
.admin-header {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  height: 64px;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-bottom: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 40px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  user-select: none;
}

.logo-box {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #0969da, #0284c7);
  display: grid;
  place-items: center;
  box-shadow: 0 2px 8px rgba(9, 105, 218, 0.25);
}

.brand-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.name {
  font-size: 15px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: #0f172a;
}

.badge-pro {
  font-size: 9.5px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(9, 105, 218, 0.1);
  color: #0969da;
  border: 1px solid rgba(9, 105, 218, 0.2);
  letter-spacing: 0.05em;
}

.sub {
  font-size: 11px;
  color: #64748b;
  margin-top: 1px;
}

/* ── 标签导航 ── */
.nav-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  background: #f1f5f9;
  padding: 4px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 14px;
  border-radius: 7px;
  border: none;
  background: transparent;
  color: #64748b;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tab-btn:hover {
  color: #0f172a;
}

.tab-btn.active {
  color: #0969da;
  background: #ffffff;
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06), 0 1px 1px rgba(0, 0, 0, 0.04);
}

.tab-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 10px;
  background: #e0e7ff;
  color: #4338ca;
  font-weight: 600;
}

/* ── 顶栏右侧 ── */
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.cluster-status {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 20px;
  font-size: 11.5px;
  color: #334155;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}

.pulse-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.6);
}

.live-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 8px;
  background: rgba(16, 185, 129, 0.1);
  border: 1px solid rgba(16, 185, 129, 0.3);
  color: #059669;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.live-dot.pulsing {
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0% { transform: scale(0.95); opacity: 0.6; }
  50% { transform: scale(1.3); opacity: 1; }
  100% { transform: scale(0.95); opacity: 0.6; }
}

.portal-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  color: #334155;
  font-size: 12.5px;
  font-weight: 500;
  text-decoration: none;
  transition: all 0.15s ease;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.portal-link:hover {
  border-color: #0969da;
  color: #0969da;
}

/* ── 主内容区 ── */
.admin-main {
  padding: 24px 32px 60px;
}

.main-container {
  max-width: 1440px;
  margin: 0 auto;
}

.view-wrapper {
  animation: fadeIn 0.25s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
