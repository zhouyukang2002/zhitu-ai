<template>
  <div class="plan-card">
    <div class="card-head">
      <span class="card-icon-badge" style="background: var(--blue-soft); color: var(--blue)">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
          <path d="M2.5 4h11M2.5 8h7M2.5 12h5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
          <circle cx="12" cy="10" r="2.5" stroke="currentColor" stroke-width="1.3"/>
          <path d="M12 9v1l.8.8" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/>
        </svg>
      </span>
      <span class="title">学习规划</span>
      <span class="subtitle">共 {{ path.length }} 阶段</span>
    </div>

    <div class="path-list">
      <div v-for="(p, i) in path" :key="p.step" class="path-item">
        <div class="path-node" :class="p.status">
          <svg v-if="p.status === 'done'" viewBox="0 0 12 12" width="10" height="10">
            <path d="M2 6.2 4.8 9 10 3.4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span v-else>{{ p.step }}</span>
        </div>
        <div v-if="i < path.length - 1" class="path-line" :class="{ lit: p.status === 'done' }" />
        <div class="path-content">
          <div class="path-title" :class="{ current: p.status === 'current' }">{{ p.title }}</div>
        </div>
      </div>
    </div>

    <div class="path-progress">
      <div class="path-progress-head">
        <span>路径完成度</span>
        <b>{{ Math.round(progress * 100) }}%</b>
      </div>
      <div class="bar-track">
        <div class="bar-fill" :style="{ width: (progress * 100) + '%' }" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ msg: { type: Object, required: true } })
const path = computed(() => props.msg.content?.path || [])
const progress = computed(() => props.msg.content?.progress ?? 0)
</script>

<style scoped>
.path-list {
  display: flex;
  flex-direction: column;
}
.path-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
}
.path-node {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 600;
  flex: none;
  background: rgba(31, 35, 40, 0.06);
  color: var(--text-3);
  position: relative;
  z-index: 1;
  transition: all var(--duration-normal) var(--ease-spring);
}
.path-node.done {
  background: var(--green-soft);
  color: var(--green);
}
.path-node.current {
  background: var(--blue);
  color: #fff;
  box-shadow: 0 0 0 3.5px var(--blue-soft);
}
.path-line {
  position: absolute;
  left: 10px;
  top: 30px;
  width: 2px;
  height: calc(100% - 20px);
  background: rgba(31, 35, 40, 0.08);
}
.path-line.lit {
  background: var(--green);
  opacity: 0.35;
}
.path-content { flex: 1; min-width: 0; }
.path-title {
  font-size: 13px;
  color: var(--text-2);
  line-height: 1.5;
}
.path-title.current {
  color: var(--text-1);
  font-weight: 600;
}

.path-progress {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--hairline);
}
.path-progress-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 12px;
  color: var(--text-2);
  margin-bottom: 7px;
}
.path-progress-head b {
  color: var(--blue);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
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
  background: var(--blue);
  transition: width 0.6s var(--ease-spring);
}
</style>
