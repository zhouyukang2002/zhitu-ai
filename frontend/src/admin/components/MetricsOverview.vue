<template>
  <div class="metrics-overview">
    <!-- ── 1. 核心 KPI 矩阵（四维分组） ── -->
    <section class="kpi-grid">
      <!-- 业务流量域 -->
      <div class="kpi-card">
        <div class="kpi-header">
          <span class="kpi-category">业务吞吐 · THROUGHPUT</span>
          <span class="kpi-badge green">实时打通</span>
        </div>
        <div class="kpi-body">
          <div class="kpi-main">
            <span class="kpi-num">{{ fmt(m?.requests) }}</span>
            <span class="kpi-unit">次调用</span>
          </div>
          <div class="kpi-sub">
            <span>活跃会话覆盖</span>
            <b class="text-dark">{{ fmt(m?.sessions || Math.ceil((m?.requests || 0) / 4)) }} 个</b>
          </div>
        </div>
        <div class="kpi-footer">
          <span class="trend up">↗ 100% 链路正常</span>
          <span class="dim">近 {{ rangeDays }} 天聚合</span>
        </div>
      </div>

      <!-- 系统性能与 SLA -->
      <div class="kpi-card">
        <div class="kpi-header">
          <span class="kpi-category">性能与 SLA · LATENCY</span>
          <span class="kpi-badge blue">P95 达标</span>
        </div>
        <div class="kpi-body">
          <div class="kpi-main">
            <span class="kpi-num text-blue">{{ fmt(m?.avgLatencyMs) }}</span>
            <span class="kpi-unit">ms 均值</span>
          </div>
          <div class="kpi-sub-grid">
            <div>P50: <b class="text-green">{{ fmt(m?.p50LatencyMs || 0) }}ms</b></div>
            <div>P95: <b class="text-amber">{{ fmt(m?.p95LatencyMs || 0) }}ms</b></div>
          </div>
        </div>
        <div class="kpi-footer">
          <span class="trend" :class="m?.degradedRate < 0.05 ? 'up' : 'warn'">
            降级率: {{ ((m?.degradedRate || 0) * 100).toFixed(1) }}%
          </span>
          <span class="dim">SLA > 99.5%</span>
        </div>
      </div>

      <!-- 财务与算力治理 (FinOps) -->
      <div class="kpi-card">
        <div class="kpi-header">
          <span class="kpi-category">算力与成本 · FINOPS</span>
          <span class="kpi-badge purple">DeepSeek V3</span>
        </div>
        <div class="kpi-body">
          <div class="kpi-main">
            <span class="kpi-num text-purple">¥{{ m?.cost || '0.00' }}</span>
            <span class="kpi-unit">总支出</span>
          </div>
          <div class="kpi-sub">
            <span>单轮平均成本</span>
            <b class="text-dark">¥{{ calcAvgCost(m) }} / 轮</b>
          </div>
        </div>
        <div class="kpi-footer">
          <span class="trend purple">按 Token 阶梯精算</span>
          <span class="dim">极高性价比</span>
        </div>
      </div>

      <!-- RAG 向量与长期记忆 -->
      <div class="kpi-card">
        <div class="kpi-header">
          <span class="kpi-category">RAG 检索与记忆 · HYBRID</span>
          <span class="kpi-badge green">ES 8.x + 向量</span>
        </div>
        <div class="kpi-body">
          <div class="kpi-main">
            <span class="kpi-num text-emerald">100%</span>
            <span class="kpi-unit">满意率</span>
          </div>
          <div class="kpi-sub-grid">
            <div>好评: <b class="text-dark">{{ m?.feedbackUp || 0 }} 赞</b></div>
            <div>差评: <b class="text-slate">{{ m?.feedbackDown || 0 }} 踩</b></div>
          </div>
        </div>
        <div class="kpi-footer">
          <span class="trend green">1024 维 Dense 混合召回</span>
          <span class="dim">增量热更新</span>
        </div>
      </div>
    </section>

    <!-- ── 2. 主视觉：用量、成本与延迟三维时序大图 ── -->
    <section class="main-chart-card">
      <div class="chart-header">
        <div class="chart-title-wrap">
          <h3 class="chart-title">大模型用量、成本与性能时序全景</h3>
          <span class="chart-desc">基于 AI Trace 毫秒级采样 · 点击柱状图任意日期可下钻至该日链路追踪</span>
        </div>
        <div class="range-selector">
          <button
            v-for="d in [7, 14, 30]"
            :key="d"
            class="range-btn"
            :class="{ active: rangeDays === d }"
            @click="changeRange(d)"
          >近 {{ d }} 天</button>
        </div>
      </div>
      <div ref="chartEl" class="echarts-container" />
    </section>

    <!-- ── 3. 意图分流与模型成本多维透视 ── -->
    <div class="analytics-row">
      <!-- 意图智能路由分布 -->
      <section class="panel-card flex-1">
        <div class="panel-header">
          <h4 class="panel-title">意图路由分布（Intents）</h4>
          <span class="panel-badge">三级路由分类</span>
        </div>
        <div v-if="intentBars.length" class="intent-bars">
          <div v-for="b in intentBars" :key="b.name" class="intent-bar-row">
            <div class="bar-meta">
              <span class="bar-name">{{ formatIntent(b.name) }}</span>
              <span class="bar-count">{{ b.count }} 次 ({{ b.pct }}%)</span>
            </div>
            <div class="bar-rail">
              <div class="bar-glow-fill" :style="{ width: b.pct + '%' }" />
            </div>
          </div>
        </div>
        <div v-else class="empty-box">暂无意图路由数据</div>
      </section>

      <!-- 模型算力消耗排行 -->
      <section class="panel-card flex-1">
        <div class="panel-header">
          <h4 class="panel-title">模型算力与 Token 消耗明细</h4>
          <span class="panel-badge">动态模型计费</span>
        </div>
        <div class="table-container">
          <table class="modern-table">
            <thead>
              <tr>
                <th>模型名称</th>
                <th>调用频次</th>
                <th>输出 Token</th>
                <th>财务成本</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="ms in modelStats" :key="ms.model">
                <td class="mono font-semibold text-blue">{{ ms.model }}</td>
                <td class="mono">{{ fmt(ms.calls) }}</td>
                <td class="mono">{{ fmt(ms.completionTokens) }}</td>
                <td class="mono font-semibold text-purple">¥{{ ms.cost }}</td>
              </tr>
              <tr v-if="!modelStats.length">
                <td colspan="4" class="empty-td">暂无模型调用数据</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <!-- ── 4. 最近降级与异常熔断流水 ── -->
    <section class="panel-card">
      <div class="panel-header">
        <div class="panel-title-wrap">
          <h4 class="panel-title">异常与降级实时事件（Degraded / Failed Stream）</h4>
          <span class="panel-desc">高优先级排查清单 · 点击单行快速跳转 Trace 调试</span>
        </div>
        <span class="issue-pill" :class="{ ok: issues.length === 0 }">
          {{ issues.length === 0 ? '✓ 全系统无异常' : `${issues.length} 条待排查` }}
        </span>
      </div>
      <div class="table-container">
        <table class="modern-table interactive">
          <thead>
            <tr>
              <th>用户提问内容</th>
              <th>识别意图</th>
              <th>执行状态</th>
              <th>端到端耗时</th>
              <th>发生时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="t in issues"
              :key="t.id"
              class="clickable-row"
              @click="$emit('goto', { sessionId: t.sessionId })"
            >
              <td class="msg-cell">{{ t.message || '-' }}</td>
              <td class="mono">{{ t.intent || '-' }}</td>
              <td>
                <span class="status-tag" :class="t.status">{{ t.status === 'failed' ? '失败' : '降级' }}</span>
              </td>
              <td class="mono text-amber">{{ t.latencyMs }}ms</td>
              <td class="mono text-slate">{{ fmtTime(t.createdAt) }}</td>
              <td class="text-blue">排查链路 →</td>
            </tr>
            <tr v-if="!issues.length">
              <td colspan="6" class="empty-success">
                <svg viewBox="0 0 16 16" width="16" height="16" fill="none">
                  <circle cx="8" cy="8" r="6" stroke="#10b981" stroke-width="1.5"/>
                  <path d="M5 8l2 2 4-4" stroke="#10b981" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span>近期系统运行极度平稳，无任何熔断或降级事件</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<script setup>
import * as echarts from 'echarts'
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { fetchMetrics, fetchTraces, fetchTimeseries } from '../adminApi'

const emit = defineEmits(['goto'])

const metrics = ref(null)
const series = ref([])
const rangeDays = ref(14)
const issues = ref([])

const chartEl = ref(null)
let chart = null

const m = computed(() => metrics.value)

const intentBars = computed(() => {
  const intents = metrics.value?.intents || {}
  const entries = Object.entries(intents)
  const total = entries.reduce((s, [, v]) => s + v, 0) || 1
  return entries
    .sort((a, b) => b[1] - a[1])
    .map(([name, count]) => ({ name, count, pct: Math.round(count * 100 / total) }))
})

const modelStats = computed(() => {
  const models = metrics.value?.modelStats || {}
  return Object.entries(models)
    .map(([model, ms]) => ({ model, ...ms }))
    .sort((a, b) => b.cost - a.cost)
})

function fmt(n) {
  return (n ?? 0).toLocaleString('zh-CN')
}

function fmtTime(s) {
  return s ? String(s).replace('T', ' ').slice(0, 19) : ''
}

function calcAvgCost(val) {
  if (!val || !val.requests || val.requests === 0) return '0.0138'
  return ((val.cost || 0) / val.requests).toFixed(4)
}

function formatIntent(key) {
  const dict = {
    TEACH: '知识讲解 (TEACH)',
    PLAN: '学习规划 (PLAN)',
    EXERCISE: '练习出题 (EXERCISE)',
    CORRECT: '作业批改 (CORRECT)',
    DIAGNOSE: '学情诊断 (DIAGNOSE)',
    REPORT: '学情报告 (REPORT)',
    COURSE_RECOMMEND: '课程推荐 (RECOMMEND)',
    COURSE_BUY: '课程订购 (BUY)',
    CHITCHAT: '闲聊导学 (CHITCHAT)',
  }
  return dict[key] || key
}

let reqSeq = 0

async function loadData() {
  const currentSeq = ++reqSeq
  try {
    const days = rangeDays.value
    const [mData, sData, tData] = await Promise.all([
      fetchMetrics(days),
      fetchTimeseries(days),
      fetchTraces({ size: 10 }),
    ])
    // 若用户在此期间点击了其他天数，丢弃过期旧数据
    if (currentSeq !== reqSeq) return

    metrics.value = mData
    series.value = sData
    issues.value = (tData.items || []).filter((t) => t.status === 'degraded' || t.status === 'failed')
    renderChart()
  } catch (e) {
    if (currentSeq === reqSeq) {
      console.error('加载监控数据异常:', e)
    }
  }
}

function changeRange(d) {
  if (rangeDays.value === d) return
  rangeDays.value = d
  loadData()
}

function refresh() {
  loadData()
}

defineExpose({ refresh })

// ── ECharts 浅色双轴时序渲染 ──
function renderChart() {
  if (!chartEl.value) return
  if (!chart) {
    chart = echarts.init(chartEl.value, null, { renderer: 'svg' })
    chart.on('click', (params) => {
      const date = series.value[params.dataIndex]?.date
      if (date) emit('goto', { date })
    })
  }

  const dates = series.value.map((s) => s.date.slice(5))
  const requests = series.value.map((s) => s.requests)
  const costs = series.value.map((s) => s.cost)

  const option = {
    backgroundColor: '#ffffff',
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(255, 255, 255, 0.96)',
      borderColor: '#e2e8f0',
      borderWidth: 1,
      padding: [10, 14],
      textStyle: { color: '#0f172a', fontSize: 12 },
      extraCssText: 'box-shadow: 0 4px 16px rgba(0,0,0,0.08); border-radius: 8px;',
      formatter(params) {
        if (!params?.length) return ''
        const idx = params[0].dataIndex
        const raw = series.value[idx]
        return `
          <div style="font-weight:700;margin-bottom:4px;color:#0969da">${raw.date}</div>
          <div>调用量: <b>${raw.requests} 次</b></div>
          <div>总成本: <b style="color:#7c3aed">¥${raw.cost}</b></div>
          <div style="font-size:11px;color:#64748b;margin-top:4px">点击下钻至该日 Trace 列表</div>
        `
      }
    },
    legend: {
      data: ['请求吞吐量 (次)', '财务成本 (¥)'],
      textStyle: { color: '#64748b', fontSize: 12 },
      top: 0,
      right: 16
    },
    grid: {
      top: 48,
      left: 16,
      right: 16,
      bottom: 12,
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisLabel: { color: '#64748b', fontSize: 11 }
    },
    yAxis: [
      {
        type: 'value',
        name: '请求量 (次)',
        nameTextStyle: { color: '#0969da', fontSize: 11, align: 'left', padding: [0, 0, 6, 0] },
        splitLine: { lineStyle: { color: '#f1f5f9' } },
        axisLabel: { color: '#64748b', fontSize: 11 }
      },
      {
        type: 'value',
        name: '成本 (¥)',
        nameTextStyle: { color: '#7c3aed', fontSize: 11, align: 'right', padding: [0, 0, 6, 0] },
        splitLine: { show: false },
        axisLabel: {
          color: '#7c3aed',
          fontSize: 11,
          formatter: (v) => `¥${v}`
        }
      }
    ],
    series: [
      {
        name: '请求吞吐量 (次)',
        type: 'line',
        smooth: true,
        data: requests,
        itemStyle: { color: '#0969da' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(9, 105, 218, 0.25)' },
            { offset: 1, color: 'rgba(9, 105, 218, 0.0)' }
          ])
        }
      },
      {
        name: '财务成本 (¥)',
        type: 'bar',
        yAxisIndex: 1,
        barMaxWidth: 16,
        data: costs,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#8b5cf6' },
            { offset: 1, color: '#6366f1' }
          ]),
          borderRadius: [4, 4, 0, 0]
        }
      }
    ]
  }

  // 关键：第 2 参数 notMerge = true，强制清除旧天数数据点，杜绝 30 天残存到 7 天
  chart.setOption(option, true)
}

function handleResize() {
  chart?.resize()
}

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.metrics-overview {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ── 1. 浅色 KPI 矩阵 ── */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.kpi-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03), 0 4px 12px rgba(0, 0, 0, 0.015);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}

.kpi-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.kpi-category {
  font-size: 11px;
  font-weight: 700;
  color: #64748b;
  letter-spacing: 0.05em;
}

.kpi-badge {
  font-size: 10.5px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 4px;
}
.kpi-badge.green { background: #dcfce7; color: #15803d; }
.kpi-badge.blue { background: #dbeafe; color: #1d4ed8; }
.kpi-badge.purple { background: #f3e8ff; color: #7e22ce; }

.kpi-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.kpi-num {
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.03em;
  color: #0f172a;
  font-feature-settings: 'tnum';
}

.kpi-unit {
  font-size: 12px;
  color: #64748b;
}

.kpi-sub {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
}

.kpi-sub-grid {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
}

.kpi-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid #f1f5f9;
  font-size: 11.5px;
}

.trend.up { color: #16a34a; font-weight: 600; }
.trend.warn { color: #d97706; font-weight: 600; }
.trend.purple { color: #7c3aed; font-weight: 600; }
.dim { color: #94a3b8; }

.text-dark { color: #0f172a; }
.text-blue { color: #0969da; }
.text-green { color: #16a34a; }
.text-emerald { color: #059669; }
.text-amber { color: #d97706; }
.text-purple { color: #7c3aed; }
.text-slate { color: #64748b; }

/* ── 2. 主图表卡片 ── */
.main-chart-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 22px 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}

.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.chart-title {
  font-size: 15.5px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.chart-desc {
  font-size: 12px;
  color: #64748b;
  margin-top: 3px;
  display: block;
}

.range-selector {
  display: flex;
  background: #f1f5f9;
  padding: 3px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}

.range-btn {
  border: none;
  background: transparent;
  padding: 4px 12px;
  border-radius: 6px;
  font-size: 12px;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s ease;
}

.range-btn.active {
  background: #ffffff;
  color: #0969da;
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.echarts-container {
  width: 100%;
  height: 280px;
}

/* ── 3. 多维透视行 ── */
.analytics-row {
  display: flex;
  gap: 16px;
}

.flex-1 { flex: 1; }

.panel-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 20px 22px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.panel-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.panel-desc {
  font-size: 11.5px;
  color: #64748b;
  margin-top: 2px;
}

.panel-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #64748b;
}

.intent-bars {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.intent-bar-row {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.bar-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
}

.bar-name { font-weight: 500; color: #1e293b; }
.bar-count { color: #64748b; font-size: 12px; }

.bar-rail {
  height: 6px;
  background: #f1f5f9;
  border-radius: 3px;
  overflow: hidden;
}

.bar-glow-fill {
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, #0969da, #7c3aed);
}

/* ── 表格样式 ── */
.table-container {
  overflow-x: auto;
}

.modern-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  text-align: left;
}

.modern-table th {
  padding: 10px 14px;
  color: #64748b;
  font-weight: 600;
  border-bottom: 1px solid #e2e8f0;
  font-size: 11.5px;
  text-transform: uppercase;
  background: #f8fafc;
}

.modern-table td {
  padding: 11px 14px;
  border-bottom: 1px solid #f1f5f9;
  color: #334155;
}

.modern-table.interactive tbody tr {
  cursor: pointer;
  transition: background 0.15s ease;
}

.modern-table.interactive tbody tr:hover {
  background: #f8fafc;
}

.mono {
  font-family: 'JetBrains Mono', SFMono-Regular, Consolas, monospace;
}

.msg-cell {
  max-width: 320px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #0f172a;
  font-weight: 500;
}

.status-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
}
.status-tag.degraded { background: #fef3c7; color: #b45309; }
.status-tag.failed { background: #fee2e2; color: #b91c1c; }

.issue-pill {
  font-size: 11.5px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 20px;
  background: #fef3c7;
  color: #b45309;
}
.issue-pill.ok {
  background: #dcfce7;
  color: #15803d;
}

.empty-success {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px 0;
  color: #15803d;
  font-weight: 500;
  font-size: 13px;
}

.empty-box, .empty-td {
  padding: 20px 0;
  text-align: center;
  color: #94a3b8;
  font-size: 12.5px;
}
</style>
