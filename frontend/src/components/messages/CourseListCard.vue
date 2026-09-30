<template>
  <div class="course-list-card">
    <div class="card-head">
      <span class="card-icon-badge" style="background: var(--purple-soft); color: var(--purple)">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
          <path d="M2.5 3.5A1.5 1.5 0 0 1 4 2h8a1.5 1.5 0 0 1 1.5 1.5v9A1.5 1.5 0 0 1 12 14H4a1.5 1.5 0 0 1-1.5-1.5v-9Z" stroke="currentColor" stroke-width="1.3"/>
          <path d="M5.5 5.5h5M5.5 8.5h5M5.5 11.5h3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
        </svg>
      </span>
      <span class="title">{{ source === 'search' ? '可选课程列表' : '为你推荐的配套课程' }}</span>
      <span class="subtitle">{{ courses.length }} 门课程</span>
    </div>

    <div class="course-items">
      <div v-for="c in courses" :key="c.courseId" class="course">
        <div class="cover">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" class="cover-icon">
            <path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z" stroke="currentColor" stroke-width="1.6"/>
            <path d="M6 6h10M6 10h8" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
          </svg>
        </div>
        <div class="course-main">
          <div class="course-top">
            <div class="course-name">{{ c.name }}</div>
            <div class="price">¥{{ c.price }}</div>
          </div>
          <div class="course-tags">
            <span v-for="t in c.tags" :key="t" class="tag">{{ t }}</span>
          </div>
          <div class="course-reason">{{ c.reason }}</div>
          <div class="course-foot">
            <span class="course-id">编号 {{ c.courseId }}</span>
            <button
              class="btn-buy"
              :disabled="state.streaming"
              @click="buy(c)"
            >
              购买课程
            </button>
          </div>
        </div>
      </div>
    </div>

    <p class="hint">点击「购买」后将生成预订单，需手动确认支付</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useChat } from '../../stores/chat'

const props = defineProps({ msg: { type: Object, required: true } })
const { state, send } = useChat()

const courses = computed(() => props.msg.content?.courses || [])
const source = computed(() => props.msg.content?.source)

function buy(c) {
  if (state.streaming) return
  send(`我要买《${c.name}》`)
}
</script>

<style scoped>
.course-items {
  display: flex;
  flex-direction: column;
}
.course {
  display: flex;
  gap: 14px;
  padding: 14px 0;
}
.course + .course {
  border-top: 1px solid var(--hairline);
}

.cover {
  flex: none;
  width: 60px;
  height: 60px;
  border-radius: var(--radius-md);
  background: var(--surface-2);
  border: 1px solid var(--hairline);
  color: var(--purple);
  display: grid;
  place-items: center;
  transition: transform var(--duration-fast) var(--ease-spring);
}
.course:hover .cover {
  transform: scale(1.03);
}

.course-main {
  flex: 1;
  min-width: 0;
}
.course-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
}
.course-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.price {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
  flex: none;
}
.course-tags {
  display: flex;
  gap: 5px;
  margin-top: 4px;
  flex-wrap: wrap;
}
.tag {
  font-size: 10px;
  color: var(--text-2);
  background: rgba(31, 35, 40, 0.05);
  border-radius: var(--radius-sm);
  padding: 2px 7px;
  font-weight: 500;
}
.course-reason {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.course-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}
.course-id {
  font-size: 11px;
  color: var(--text-3);
  font-family: var(--font-mono);
}
.btn-buy {
  padding: 5px 14px;
  border-radius: 980px;
  border: none;
  background: var(--blue);
  color: #fff;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-smooth),
              transform var(--duration-fast) var(--ease-smooth);
}
.btn-buy:hover:not(:disabled) {
  background: var(--blue-hover);
  transform: translateY(-1px);
}
.btn-buy:active:not(:disabled) {
  transform: translateY(0);
}
.btn-buy:disabled {
  opacity: 0.5;
  cursor: default;
}

.hint {
  margin: 12px 0 0;
  padding-top: 10px;
  border-top: 1px solid var(--hairline);
  font-size: 11px;
  color: var(--text-3);
  text-align: center;
}
</style>
