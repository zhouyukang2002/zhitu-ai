<template>
  <div class="report-card">
    <div class="card-head">
      <span class="card-icon-badge" style="background: var(--cyan-soft); color: var(--cyan)">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
          <path d="M2.5 13.5h11M4 11V7.5M8 11V4.5M12 11V2.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
      </span>
      <span class="title">学情综合报告</span>
    </div>

    <div class="metrics">
      <div v-for="m in metrics" :key="m.label" class="metric">
        <div class="metric-head">
          <span class="metric-label">{{ m.label }}</span>
          <span class="metric-val">
            <b>{{ m.value }}</b><i v-if="m.max"> / {{ m.max }}</i>
          </span>
        </div>
        <div class="bar-track">
          <div
            class="bar-fill"
            :style="{ width: ((m.value / (m.max || 100)) * 100) + '%' }"
          />
        </div>
      </div>
    </div>

    <p v-if="summary" class="summary">{{ summary }}</p>
    <div v-if="trend" class="trend">
      <svg viewBox="0 0 16 16" width="13" height="13" fill="none" style="flex:none">
        <path d="M2 11.5l4-4 3 3 5-5.5M11 5h3v3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
      <span>{{ trend }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ msg: { type: Object, required: true } })
const metrics = computed(() => props.msg.content?.metrics || [])
const summary = computed(() => props.msg.content?.summary)
const trend = computed(() => props.msg.content?.trend)
</script>

<style scoped>
.metrics {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.metric { }
.metric-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 12.5px;
  margin-bottom: 5px;
}
.metric-label {
  color: var(--text-2);
}
.metric-val b {
  font-size: 13px;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
}
.metric-val i {
  font-style: normal;
  font-size: 10.5px;
  font-weight: 400;
  color: var(--text-3);
}
.bar-track {
  height: 6px;
  background: rgba(31, 35, 40, 0.06);
  border-radius: 980px;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 980px;
  background: var(--cyan);
  transition: width 0.6s var(--ease-spring);
}

.summary {
  margin: 14px 0 0;
  padding: 10px 14px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--hairline);
  border-radius: var(--radius-md);
}
.trend {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--green);
  font-weight: 500;
}
</style>
