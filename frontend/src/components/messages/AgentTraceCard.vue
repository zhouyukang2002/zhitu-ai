<template>
  <div class="trace-card">
    <div class="card-head">
      <span class="card-icon-badge" style="background: var(--purple-soft); color: var(--purple)">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
          <circle cx="4" cy="8" r="2" stroke="currentColor" stroke-width="1.3"/>
          <circle cx="12" cy="4" r="2" stroke="currentColor" stroke-width="1.3"/>
          <circle cx="12" cy="12" r="2" stroke="currentColor" stroke-width="1.3"/>
          <path d="M6 7.3l4-2.3M6 8.7l4 2.3" stroke="currentColor" stroke-width="1.3"/>
        </svg>
      </span>
      <span class="title">执行链路</span>
      <span class="subtitle">{{ steps.length }} 个节点 · 耗时 {{ totalMs }}ms</span>
    </div>
    <el-collapse>
      <el-collapse-item :title="`智能体调用链路（${steps.length} 步）`" name="trace">
        <div v-for="(s, i) in steps" :key="i" class="trace-row">
          <span class="status-dot" :class="statusClass(s.status)" :title="s.status" />
          <div class="trace-main">
            <div class="trace-top">
              <span class="agent">{{ s.agent }}</span>
              <span class="cost">{{ s.costMs }}ms</span>
            </div>
            <div class="trace-sub">{{ s.action }}<template v-if="s.tool"> · {{ s.tool }}</template></div>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ msg: { type: Object, required: true } })
const steps = computed(() => props.msg.content?.steps || [])
const totalMs = computed(() => steps.value.reduce((sum, s) => sum + (s.costMs || 0), 0))

function statusClass(status) {
  if (status === 'failed') return 'failed'
  if (status && status !== 'success') return 'degraded'
  return 'success'
}
</script>

<style scoped>
.trace-card :deep(.el-collapse) {
  border: none;
  --el-collapse-header-height: 32px;
}
.trace-card :deep(.el-collapse-item__header) {
  font-size: 12.5px;
  color: var(--text-2);
  border-bottom: none;
}
.trace-card :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}
.trace-card :deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

.trace-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 0;
}
.trace-row + .trace-row {
  border-top: 1px solid var(--hairline);
}
.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-top: 6px;
  flex: none;
}
.status-dot.success  { background: var(--green); }
.status-dot.failed   { background: var(--red); }
.status-dot.degraded { background: var(--orange); }

.trace-main { flex: 1; min-width: 0; }
.trace-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.agent {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}
.cost {
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
  font-family: var(--font-mono);
}
.trace-sub {
  font-size: 12px;
  color: var(--text-2);
  margin-top: 3px;
  line-height: 1.5;
}
</style>
