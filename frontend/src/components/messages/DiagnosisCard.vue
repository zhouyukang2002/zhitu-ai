<template>
  <div class="diagnosis-card">
    <div class="card-head">
      <span class="card-icon-badge" style="background: var(--orange-soft); color: var(--orange)">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
          <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.3"/>
          <path d="M8 5v3.5l2 1.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
        </svg>
      </span>
      <span class="title">学情诊断</span>
      <span class="subtitle">{{ weakPoints.length }} 个薄弱点</span>
    </div>

    <div class="weak-list">
      <div v-for="w in weakPoints" :key="w.knowledgePoint" class="weak-item">
        <div class="weak-head">
          <span class="weak-name">{{ w.knowledgePoint }}</span>
          <span class="weak-meta">
            <span class="conf-tag" :class="confClass(w.confidence)">{{ w.confidence }}置信</span>
            <span class="weak-score" :class="scoreClass(w.score)">{{ w.score }}<i>/100</i></span>
          </span>
        </div>
        <div class="bar-track">
          <div
            class="bar-fill"
            :class="scoreClass(w.score)"
            :style="{ width: w.score + '%' }"
          />
        </div>
      </div>
    </div>

    <p v-if="summary" class="summary">{{ summary }}</p>

    <!-- 旁路入口：诊断结果触发课程推荐 -->
    <div class="trade-entry">
      <button class="btn-recommend" :disabled="state.streaming" @click="recommend">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none" style="margin-right: 5px">
          <path d="M2.5 3.5h11M2.5 8h11M2.5 12.5h7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
        </svg>
        查看配套提升课程
      </button>
      <span class="trade-tip">基于薄弱点智能推荐</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useChat } from '../../stores/chat'

const props = defineProps({ msg: { type: Object, required: true } })
const { state, send } = useChat()
const weakPoints = computed(() => props.msg.content?.weakPoints || [])
const summary = computed(() => props.msg.content?.summary)

function recommend() {
  if (state.streaming) return
  send('根据我的薄弱点推荐配套课程')
}

function confClass(c) {
  return c === '高' ? 'conf-high' : c === '中' ? 'conf-mid' : 'conf-low'
}

function scoreClass(s) {
  return s < 60 ? 'score-warn' : 'score-ok'
}
</script>

<style scoped>
.weak-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.weak-item { }
.weak-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.weak-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}
.weak-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.conf-tag {
  font-size: 10.5px;
  font-weight: 500;
  padding: 2px 7px;
  border-radius: var(--radius-sm);
}
.conf-high { background: var(--red-soft); color: var(--red); }
.conf-mid  { background: var(--orange-soft); color: var(--orange); }
.conf-low  { background: var(--blue-soft); color: var(--blue); }

.weak-score {
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.weak-score i {
  font-style: normal;
  font-size: 10.5px;
  font-weight: 400;
  color: var(--text-3);
}
.score-warn { color: var(--orange); }
.score-ok   { color: var(--green); }

.bar-track {
  height: 6px;
  background: rgba(31, 35, 40, 0.06);
  border-radius: 980px;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 980px;
  transition: width 0.6s var(--ease-spring);
}
.bar-fill.score-warn { background: var(--orange); }
.bar-fill.score-ok   { background: var(--green); }

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
.trade-entry {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--hairline);
}
.btn-recommend {
  display: inline-flex;
  align-items: center;
  padding: 6px 14px;
  border-radius: 980px;
  border: 1px solid var(--hairline-strong);
  background: var(--surface);
  color: var(--text-1);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-smooth);
}
.btn-recommend:hover {
  border-color: var(--blue);
  color: var(--blue);
  background: var(--blue-soft);
}
.btn-recommend:disabled { opacity: 0.5; cursor: default; }
.trade-tip {
  font-size: 11px;
  color: var(--text-3);
}
</style>
