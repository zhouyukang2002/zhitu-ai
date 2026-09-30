<template>
  <div class="grade-card">
    <div class="card-head">
      <span class="card-icon-badge" :style="{ background: passed ? 'var(--green-soft)' : 'var(--orange-soft)', color: passed ? 'var(--green)' : 'var(--orange)' }">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
          <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.3"/>
          <path d="M5.5 8l2 2 3.5-4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </span>
      <span class="title">批改结果</span>
      <span class="subtitle">{{ knowledgePoints.join(' · ') }}</span>
    </div>

    <div class="score-row">
      <div class="score-hero">
        <span class="score-val" :class="passed ? 'text-green' : score >= 60 ? 'text-orange' : 'text-red'">{{ score }}</span>
        <span class="score-total">/ {{ totalScore }}</span>
      </div>
      <div class="score-meta">
        <div class="score-badge" :class="passed ? 'badge-green' : score >= 60 ? 'badge-orange' : 'badge-red'">
          {{ passed ? '成绩优秀' : score >= 60 ? '表现良好' : '仍需巩固' }}
        </div>
        <div class="score-sub">{{ correctCount }} / {{ perQuestion.length }} 题正确</div>
      </div>
    </div>

    <div class="per-question">
      <div v-for="q in perQuestion" :key="q.id" class="pq" :class="{ wrong: !q.correct }">
        <div class="pq-head">
          <span class="pq-icon" :class="q.correct ? 'ok' : 'bad'">
            <svg v-if="q.correct" viewBox="0 0 14 14" width="11" height="11">
              <path d="M2.5 7.4 5.7 10.5 11.5 3.8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <svg v-else viewBox="0 0 14 14" width="11" height="11">
              <path d="M3.5 3.5 10.5 10.5 M10.5 3.5 3.5 10.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </span>
          <span class="pq-title">第 {{ q.id.replace(/\D/g, '') || q.id }} 题</span>
          <span v-if="q.errorType" class="error-type-tag">{{ q.errorType }}</span>
        </div>
        <div class="pq-answer">
          <span class="label">参考答案</span>
          <span class="value">{{ q.answer }}</span>
        </div>
        <p class="pq-feedback">{{ q.feedback }}</p>
        <!-- 错题举一反三变式训练 -->
        <div v-if="!q.correct" class="variant-wrap">
          <button
            class="variant-btn"
            :disabled="loadingVariantId === q.id"
            @click="requestVariant(q)"
          >
            <svg viewBox="0 0 16 16" width="12" height="12" fill="none">
              <path d="M2.5 8a5.5 5.5 0 0 1 9.39-3.89l1.61-1.61v4.5h-4.5l1.7-1.7A3.5 3.5 0 1 0 11.5 8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            {{ loadingVariantId === q.id ? '正在生成变式题...' : '🔁 举一反三·变式题巩固' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from '../../api/request'
import { useChat } from '../../stores/chat'

const props = defineProps({ msg: { type: Object, required: true } })
const { state } = useChat()

const score = computed(() => props.msg.content?.score ?? 0)
const totalScore = computed(() => props.msg.content?.totalScore ?? 100)
const perQuestion = computed(() => props.msg.content?.perQuestion || [])
const knowledgePoints = computed(() => props.msg.content?.knowledgePoints || [])
const passed = computed(() => score.value >= 80)
const correctCount = computed(() => perQuestion.value.filter((q) => q.correct).length)

const loadingVariantId = ref(null)

async function requestVariant(q) {
  loadingVariantId.value = q.id
  try {
    const res = await request('/api/exercise/variant', {
      method: 'POST',
      body: {
        sessionId: state.currentId,
        questionId: q.id,
        kp: knowledgePoints.value[0] || 'JavaSE 基础',
        errorType: q.errorType || '技术概念混淆',
        stem: q.stem || '',
      }
    })
    ElMessage.success('已为你生成「' + (knowledgePoints.value[0] || '该考点') + '」举一反三变式题！')
    state.messages.push({
      id: 'msg_' + Date.now(),
      type: 'exercise',
      role: 'assistant',
      content: res,
      createdAt: Date.now(),
    })
  } catch (e) {
    ElMessage.error(e.message || '生成变式题失败')
  } finally {
    loadingVariantId.value = null
  }
}
</script>

<style scoped>
.score-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 6px 0 16px;
}
.score-hero {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.score-val {
  font-size: 36px;
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.score-total {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--text-3);
}
.text-green  { color: var(--green); }
.text-orange { color: var(--orange); }
.text-red    { color: var(--red); }

.score-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.score-badge {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: var(--radius-sm);
  width: fit-content;
}
.badge-green  { background: var(--green-soft); color: var(--green); }
.badge-orange { background: var(--orange-soft); color: var(--orange); }
.badge-red    { background: var(--red-soft); color: var(--red); }

.score-sub {
  font-size: 11.5px;
  color: var(--text-3);
}

.per-question {
  border-top: 1px solid var(--hairline);
}
.pq {
  padding: 12px 0;
}
.pq + .pq {
  border-top: 1px solid var(--hairline);
}
.pq-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.pq-icon {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: none;
}
.pq-icon.ok  { background: var(--green-soft); color: var(--green); }
.pq-icon.bad { background: var(--red-soft); color: var(--red); }
.pq-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}
.error-type-tag {
  background: var(--orange-soft);
  color: var(--orange);
  font-size: 10.5px;
  font-weight: 500;
  padding: 2px 7px;
  border-radius: var(--radius-sm);
}
.pq-answer {
  display: flex;
  gap: 8px;
  font-size: 12.5px;
  padding: 7px 12px;
  background: var(--surface-2);
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm);
}
.pq-answer .label {
  flex: none;
  color: var(--text-3);
}
.pq-answer .value {
  color: var(--text-1);
  line-height: 1.55;
  word-break: break-all;
}
.pq-feedback {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text-2);
}

.variant-wrap {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}
.variant-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--blue);
  background: rgba(9, 105, 218, 0.08);
  border: 1px solid rgba(9, 105, 218, 0.2);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
}
.variant-btn:hover:not(:disabled) {
  background: var(--blue);
  color: #fff;
  box-shadow: 0 2px 6px rgba(9, 105, 218, 0.25);
  transform: translateY(-1px);
}
.variant-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
