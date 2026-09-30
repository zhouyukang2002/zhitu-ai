<template>
  <div class="eval-root">
    <!-- ── 1. 顶部控制栏 ── -->
    <div class="control-bar">
      <div class="control-left">
        <h3 class="section-title">模型质量与三路加权批量评估</h3>
        <span class="section-sub">综合评分模型：规则指标 × 60% + LLM Judge 语义仲裁 × 25% + 真实用户反馈 × 15%</span>
      </div>
      <div class="control-right">
        <select v-model.number="days" class="filter-select">
          <option :value="1">近 1 天数据</option>
          <option :value="7">近 7 天数据</option>
          <option :value="30">近 30 天数据</option>
        </select>
        <label class="judge-toggle">
          <span class="toggle-switch" :class="{ on: includeJudge }" @click="includeJudge = !includeJudge">
            <span class="toggle-thumb" />
          </span>
          <span class="toggle-text">启用 LLM Judge 智能仲裁</span>
        </label>
        <button class="btn-primary" :class="{ loading: running }" :disabled="running" @click="run">
          <span v-if="running" class="spin">⟳</span>
          {{ running ? '深度评估中…' : '一键运行批量评估' }}
        </button>
        <span v-if="error" class="eval-error">{{ error }}</span>
      </div>
    </div>

    <!-- ── 2. 评估结果仪表盘 ── -->
    <transition name="fade-up">
      <div v-if="report" class="report-grid">
        <!-- 左侧：综合评分环 -->
        <div class="score-card">
          <div class="score-ring-wrap">
            <div class="score-ring" :style="{ background: ringBg }">
              <div class="ring-hole">
                <b class="ring-score" :style="{ color: scoreColor }">{{ report.avgScore }}</b>
                <span class="ring-label">综合质量分</span>
              </div>
            </div>
          </div>
          <div class="score-meta">
            <div class="score-grade-badge" :class="gradeClass">{{ gradeLabel }}</div>
            <div class="score-details">
              <div class="detail-row">
                <span class="dot blue" />
                <span>评估 Trace 总样本: <b class="text-dark">{{ report.count }}</b> 条</span>
              </div>
              <div class="detail-row">
                <span class="dot green" />
                <span>已人工金标准标注: <b class="text-dark">{{ report.labeledCount }}</b> 条</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧：细分维度矩阵 -->
        <div class="metric-card">
          <div class="metric-card-head">
            <h4 class="metric-title">核心维度细分指标（0 ~ 100 分制）</h4>
            <span class="metric-sub">基于已标注金标准及规则引擎计算</span>
          </div>
          <div class="metric-list">
            <div v-for="m in metricBars" :key="m.name" class="m-row">
              <span class="m-name">{{ metricName(m.name) }}</span>
              <div class="m-track">
                <div class="m-fill" :class="fillClass(m.value)" :style="{ width: (m.value * 100) + '%' }" />
              </div>
              <span class="m-value mono" :class="fillClass(m.value)">{{ (m.value * 100).toFixed(1) }}</span>
            </div>
            <div v-if="!metricBars.length" class="m-empty">暂无细分指标数据（可通过标注集沉淀）</div>
          </div>
          <p class="metric-note">💡 提示：意图与槽位准确率依赖 Trace 标注，标注样本越多，模型迭代回归评估越可信。</p>
        </div>
      </div>
    </transition>

    <!-- ── 3. 低分样本排查与回流清单 ── -->
    <transition name="fade-up">
      <div v-if="report" class="low-card">
        <div class="low-head">
          <div class="low-title-wrap">
            <h4 class="low-title">低分样本诊断与回流清单（Low Score Samples）</h4>
            <span class="low-desc">综合质量分 &lt; 80 分的异常或低置信请求 · 优先进入模型微调与提示词优化数据集</span>
          </div>
          <span class="low-badge">{{ report.lowScoreSamples?.length || 0 }} 条需治理</span>
        </div>
        <div class="table-wrap">
          <table class="low-table">
            <thead>
              <tr>
                <th>Trace ID</th>
                <th>会话 ID</th>
                <th>用户原始输入消息</th>
                <th>质量评分</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(s, i) in report.lowScoreSamples" :key="s.traceId" :class="{ even: i % 2 === 0 }">
                <td class="mono text-blue">{{ s.traceId }}</td>
                <td class="mono dim">{{ s.sessionId }}</td>
                <td class="cell-msg text-dark">{{ s.message }}</td>
                <td>
                  <span class="score-pill" :class="scorePillClass(s.score)">{{ s.score }} 分</span>
                </td>
                <td>
                  <span class="status-pill" :class="s.status">{{ statusLabel(s.status) }}</span>
                </td>
              </tr>
              <tr v-if="!report.lowScoreSamples?.length">
                <td colspan="5" class="empty-success-row">
                  <svg viewBox="0 0 16 16" width="16" height="16" fill="none">
                    <circle cx="8" cy="8" r="6" stroke="#10b981" stroke-width="1.5"/>
                    <path d="M5 8l2 2 4-4" stroke="#10b981" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                  <span>极度优秀！当前评估范围内全部样本质量达标（无 &lt; 80 分样本）</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </transition>

    <!-- ── 4. 空状态占位引导 ── -->
    <div v-if="!report && !running" class="placeholder-card">
      <div class="placeholder-icon-wrap">
        <svg viewBox="0 0 24 24" width="40" height="40" fill="none">
          <rect x="3" y="4" width="18" height="16" rx="3" stroke="#0969da" stroke-width="1.8"/>
          <path d="M7 14v2M12 10v6M17 7v9" stroke="#0969da" stroke-width="2" stroke-linecap="round"/>
        </svg>
      </div>
      <h3 class="placeholder-title">运行企业级批量质量评测</h3>
      <p class="placeholder-desc">
        选择评估时间窗口并点击「一键运行批量评估」，系统将自动拉取全部 Trace 记录，通过
        <b class="text-dark">规则评分 (60%) + LLM Judge 语义仲裁 (25%) + 用户反馈 (15%)</b> 输出综合质量大盘与低分回流样本。
      </p>
      <button class="btn-primary btn-lg" @click="run">立即运行评估</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { runEval } from '../adminApi'

const days = ref(7)
const includeJudge = ref(true)
const running = ref(false)
const error = ref('')
const report = ref(null)

const scoreColor = computed(() => {
  const s = report.value?.avgScore ?? 0
  return s >= 90 ? '#10b981' : s >= 70 ? '#0969da' : '#f59e0b'
})

const ringBg = computed(() => {
  const s = report.value?.avgScore ?? 0
  const c = s >= 90 ? '#10b981' : s >= 70 ? '#0969da' : '#f59e0b'
  return `conic-gradient(${c} ${s * 3.6}deg, #f1f5f9 0deg)`
})

const gradeClass = computed(() => {
  const s = report.value?.avgScore ?? 0
  return s >= 90 ? 'grade-a' : s >= 70 ? 'grade-b' : 'grade-c'
})

const gradeLabel = computed(() => {
  const s = report.value?.avgScore ?? 0
  return s >= 90 ? 'LEVEL A+ · 卓越品质' : s >= 70 ? 'LEVEL B · 良好受控' : 'LEVEL C · 待治理'
})

const metricBars = computed(() =>
  Object.entries(report.value?.metricAverages || {})
    .filter(([, v]) => v != null)
    .map(([name, value]) => ({ name, value })),
)

const METRIC_NAMES = {
  intentAccuracy: '意图识别准确率',
  slotAccuracy:   '槽位抽取完整率',
  latencyScore:   '响应延迟达标率',
  costScore:      'Token 成本精算分',
  fallbackScore:  '非降级全闭环分',
}
const metricName = (k) => METRIC_NAMES[k] || k

function fillClass(v) {
  if (v >= 0.9) return 'fill-green'
  if (v >= 0.7) return 'fill-blue'
  return 'fill-orange'
}

function scorePillClass(s) {
  return s >= 80 ? 'score-ok' : s >= 60 ? 'score-warn' : 'score-bad'
}

function statusLabel(s) {
  return { success: 'SUCCESS', degraded: 'DEGRADED', failed: 'FAILED' }[s] || s || '—'
}

async function run() {
  running.value = true
  error.value = ''
  report.value = null
  try {
    report.value = await runEval({
      days: days.value,
      includeJudge: includeJudge.value,
    })
  } catch (e) {
    error.value = e.message || '评估运行失败'
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.eval-root {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ── 1. 浅色控制栏 ── */
.control-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px 22px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}

.section-title {
  font-size: 15.5px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 3px;
}

.section-sub {
  font-size: 12px;
  color: #64748b;
}

.control-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.filter-select {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 6px 12px;
  color: #0f172a;
  font-size: 12.5px;
  outline: none;
}

.judge-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}

.toggle-switch {
  width: 34px;
  height: 20px;
  border-radius: 10px;
  background: #cbd5e1;
  position: relative;
  transition: background 0.2s ease;
}

.toggle-switch.on {
  background: #0969da;
}

.toggle-thumb {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  position: absolute;
  top: 3px;
  left: 3px;
  transition: transform 0.2s ease;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}

.toggle-switch.on .toggle-thumb {
  transform: translateX(14px);
}

.toggle-text {
  font-size: 12px;
  color: #334155;
}

.btn-primary {
  background: #0969da;
  color: #fff;
  border: none;
  padding: 7px 18px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-primary:hover { background: #054da7; }
.btn-primary.btn-lg { padding: 10px 24px; font-size: 14px; margin-top: 14px; }

.eval-error {
  color: #ef4444;
  font-size: 12px;
}

/* ── 2. 报告矩阵 ── */
.report-grid {
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: 16px;
}

.score-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}

.score-ring {
  width: 140px;
  height: 140px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  box-shadow: 0 2px 12px rgba(16, 185, 129, 0.15);
}

.ring-hole {
  width: 112px;
  height: 112px;
  border-radius: 50%;
  background: #ffffff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.ring-score {
  font-size: 34px;
  font-weight: 800;
  letter-spacing: -0.04em;
}

.ring-label {
  font-size: 11px;
  color: #64748b;
  margin-top: 2px;
}

.score-meta {
  margin-top: 20px;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.score-grade-badge {
  font-size: 12px;
  font-weight: 700;
  padding: 4px 14px;
  border-radius: 20px;
}
.grade-a { background: #dcfce7; color: #15803d; }
.grade-b { background: #dbeafe; color: #1d4ed8; }
.grade-c { background: #fef3c7; color: #b45309; }

.score-details {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12px;
  color: #64748b;
}

.detail-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot { width: 6px; height: 6px; border-radius: 50%; }
.dot.blue { background: #0969da; }
.dot.green { background: #10b981; }

.metric-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 22px 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}

.metric-card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 18px;
}

.metric-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.metric-sub {
  font-size: 11.5px;
  color: #64748b;
}

.metric-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.m-row {
  display: grid;
  grid-template-columns: 140px 1fr 50px;
  align-items: center;
  gap: 14px;
}

.m-name {
  font-size: 12.5px;
  font-weight: 500;
  color: #334155;
}

.m-track {
  height: 8px;
  background: #f1f5f9;
  border-radius: 4px;
  overflow: hidden;
}

.m-fill {
  height: 100%;
  border-radius: 4px;
}
.m-fill.fill-green { background: linear-gradient(90deg, #10b981, #34d399); }
.m-fill.fill-blue { background: linear-gradient(90deg, #0969da, #60a5fa); }
.m-fill.fill-orange { background: linear-gradient(90deg, #d97706, #fbbf24); }

.m-value {
  font-size: 13px;
  font-weight: 700;
  text-align: right;
}
.m-value.fill-green { color: #15803d; }
.m-value.fill-blue { color: #0969da; }
.m-value.fill-orange { color: #b45309; }

.metric-note {
  margin-top: 18px;
  font-size: 11.5px;
  color: #64748b;
}

/* ── 3. 低分清单 ── */
.low-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 20px 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}

.low-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.low-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 2px;
}

.low-desc {
  font-size: 11.5px;
  color: #64748b;
}

.low-badge {
  font-size: 11.5px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 20px;
  background: #fef3c7;
  color: #b45309;
}

.low-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  text-align: left;
}

.low-table th {
  padding: 10px 14px;
  color: #64748b;
  font-size: 11.5px;
  border-bottom: 1px solid #e2e8f0;
  text-transform: uppercase;
  background: #f8fafc;
}

.low-table td {
  padding: 11px 14px;
  border-bottom: 1px solid #f1f5f9;
}

.score-pill {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 4px;
}
.score-pill.score-ok { background: #dcfce7; color: #15803d; }
.score-pill.score-warn { background: #fef3c7; color: #b45309; }
.score-pill.score-bad { background: #fee2e2; color: #b91c1c; }

.status-pill {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 4px;
}
.status-pill.success { background: #dcfce7; color: #15803d; }
.status-pill.degraded { background: #fef3c7; color: #b45309; }
.status-pill.failed { background: #fee2e2; color: #b91c1c; }

.empty-success-row {
  text-align: center;
  padding: 24px 0;
  color: #15803d;
  font-weight: 500;
}

/* ── 4. 占位卡片 ── */
.placeholder-card {
  background: #ffffff;
  border: 1px dashed #cbd5e1;
  border-radius: 14px;
  padding: 60px 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.placeholder-icon-wrap {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  background: #eff6ff;
  display: grid;
  place-items: center;
  margin-bottom: 16px;
}

.placeholder-title {
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 8px;
}

.placeholder-desc {
  max-width: 520px;
  font-size: 13.5px;
  color: #64748b;
  line-height: 1.6;
}

.mono { font-family: 'JetBrains Mono', Consolas, monospace; }
.text-dark { color: #0f172a; }
.text-blue { color: #0969da; }
.dim { color: #94a3b8; }
.spin { display: inline-block; animation: spin 1s infinite linear; }
@keyframes spin { 100% { transform: rotate(360deg); } }
</style>
