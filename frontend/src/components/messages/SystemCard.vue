<template>
  <!-- 事件型系统卡：按 state 分级呈现（摸底邀请 / 转人工 / 安全拦截），默认保持细线分隔样式 -->
  <div v-if="eventStyle" class="system-event" :class="eventStyle.cls">
    <span class="icon">{{ eventStyle.icon }}</span>
    <div class="body">
      <div class="title">{{ eventStyle.title }}</div>
      <div class="text">{{ msg.content?.text }}</div>
    </div>
  </div>
  <div v-else class="system-card">
    <span class="line" />
    <span class="text">{{ msg.content?.text }}</span>
    <span class="line" />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ msg: { type: Object, required: true } })

const EVENT_STYLES = {
  PLACEMENT: { cls: 'event-placement', icon: '📋', title: '摸底测试' },
  HUMAN_HANDOFF: { cls: 'event-handoff', icon: '👤', title: '已转接人工老师' },
  SAFETY_BLOCKED: { cls: 'event-safety', icon: '🛡️', title: '安全防护提示' },
  REPLANNED: { cls: 'event-replan', icon: '🔄', title: '学习计划已调整' },
}
const eventStyle = computed(() => EVENT_STYLES[props.msg.content?.state] ?? null)
</script>

<style scoped>
.system-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 2px 8px;
}
.line {
  flex: 1;
  height: 0.5px;
  background: var(--hairline);
}
.text {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}

/* 事件型系统卡 */
.system-event {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 4px 0;
  padding: 10px 14px;
  border-radius: 10px;
  border: 1px solid var(--hairline);
  background: #f7f8fa;
}
.system-event .icon {
  font-size: 18px;
  line-height: 1.4;
}
.system-event .title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 2px;
}
.system-event .text {
  font-size: 13px;
  line-height: 1.5;
  color: #444;
}

.event-placement {
  border-color: #bcd4f7;
  background: #f0f6ff;
}
.event-placement .title {
  color: #2f6bd8;
}

.event-handoff {
  border-color: #f5c88a;
  background: #fff8ef;
}
.event-handoff .title {
  color: #c07818;
}

.event-safety {
  border-color: #f0b6b6;
  background: #fdf2f2;
}
.event-safety .title {
  color: #c0504d;
}

.event-replan {
  border-color: #bfe3c8;
  background: #f2fbf5;
}
.event-replan .title {
  color: #2e8b4f;
}
</style>
