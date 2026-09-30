<template>
  <div class="exercise-card">
    <div class="card-head">
      <span class="card-icon-badge" style="background: var(--green-soft); color: var(--green)">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
          <path d="M3 3h10v10H3z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
          <path d="M5.5 6.5h5M5.5 9.5h3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
        </svg>
      </span>
      <span class="title">随堂练习</span>
      <span class="subtitle">{{ questions.length }} 道测试题</span>
    </div>
    <p v-if="tip" class="tip">{{ tip }}</p>

    <div v-for="(q, qi) in questions" :key="q.id" class="question">
      <div class="q-stem">
        <span class="q-index">{{ qi + 1 }}</span>
        <span class="q-text">{{ q.stem }}</span>
        <span v-if="q.type === 'short'" class="q-type">简答题</span>
      </div>

      <!-- 选择题：点击行选择 -->
      <div v-if="q.type === 'choice'" class="options">
        <div
          v-for="opt in q.options"
          :key="opt"
          class="option"
          :class="{ selected: answers[q.id] === opt.charAt(0) }"
          @click="!locked && (answers[q.id] = opt.charAt(0))"
        >
          <span class="option-badge">{{ opt.charAt(0) }}</span>
          <span class="option-text">{{ opt.slice(2) }}</span>
          <svg v-if="answers[q.id] === opt.charAt(0)" class="option-check" viewBox="0 0 14 14" width="13" height="13">
            <path d="M2.5 7.4 5.7 10.5 11.5 3.8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
      </div>

      <!-- 简答题：多行输入 -->
      <el-input
        v-else
        v-model="answers[q.id]"
        type="textarea"
        :autosize="{ minRows: 3, maxRows: 6 }"
        :disabled="locked"
        placeholder="写出你的思考过程或解题结论…"
      />
    </div>

    <div v-if="error" class="validate-error">{{ error }}</div>

    <div class="actions">
      <button
        class="btn-submit"
        :class="{ loading: submitting, done: submitted }"
        :disabled="locked"
        @click="submit"
      >
        <span v-if="submitting" class="spin">⟳</span>
        {{ submitted ? '已提交批改' : submitting ? '批改中…' : '提交答案' }}
      </button>
      <span v-if="submitted" class="submitted-note">批改结果已生成，请见下方</span>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { useChat } from '../../stores/chat'

const props = defineProps({ msg: { type: Object, required: true } })

const { state, doSubmit, submittingIds } = useChat()

const exerciseId = computed(() => props.msg.content?.exerciseId)
const questions = computed(() => props.msg.content?.questions || [])
const tip = computed(() => props.msg.content?.tip)

const answers = reactive({})
const error = ref('')

const submitting = computed(() => !!submittingIds[exerciseId.value])
const submitted = computed(() => !!state.submitted[exerciseId.value])
const locked = computed(() => submitting.value || submitted.value)

async function submit() {
  const missing = questions.value.filter((q) => {
    const v = (answers[q.id] ?? '').toString().trim()
    return q.type === 'choice' ? !v : !v
  })
  if (missing.length) {
    error.value = `还有 ${missing.length} 题未作答，请完成后再提交`
    return
  }
  error.value = ''
  await doSubmit(exerciseId.value, questions.value.map((q) => ({ id: q.id, answer: (answers[q.id] ?? '').toString().trim() })))
}
</script>

<style scoped>
.tip {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--text-3);
}
.question {
  padding: 12px 0;
}
.question + .question {
  border-top: 1px solid var(--hairline);
}
.q-stem {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 10px;
}
.q-index {
  flex: none;
  width: 18px;
  height: 18px;
  border-radius: 5px;
  background: var(--blue-soft);
  color: var(--blue);
  font-size: 11px;
  font-weight: 700;
  display: grid;
  place-items: center;
  margin-top: 1px;
}
.q-text {
  flex: 1;
  font-size: 13.5px;
  line-height: 1.6;
  font-weight: 500;
}
.q-type {
  flex: none;
  font-size: 10.5px;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  background: var(--orange-soft);
  color: var(--orange);
  font-weight: 500;
}

/* 选项 */
.options {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-left: 26px;
}
.option {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-smooth);
  user-select: none;
  background: var(--surface);
}
.option:hover {
  border-color: var(--blue, #0969da);
  background: var(--surface-2);
  transform: translateX(3px);
  box-shadow: 0 2px 6px rgba(9, 105, 218, 0.08);
}
.option.selected {
  border-color: var(--blue);
  background: var(--blue-soft);
  box-shadow: 0 2px 8px rgba(9, 105, 218, 0.12);
}
.option-badge {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 1px solid var(--hairline-strong);
  color: var(--text-2);
  font-size: 11px;
  font-weight: 600;
  display: grid;
  place-items: center;
  transition: all var(--duration-fast) var(--ease-smooth);
}
.option.selected .option-badge {
  background: var(--blue);
  border-color: var(--blue);
  color: #fff;
}
.option-text {
  flex: 1;
  font-size: 13px;
  color: var(--text-1);
}
.option-check {
  color: var(--blue);
  flex: none;
}

.validate-error {
  margin-top: 10px;
  font-size: 12px;
  color: var(--red);
}
.actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.btn-submit {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 18px;
  border-radius: 980px;
  border: none;
  background: var(--blue);
  color: #fff;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-smooth),
              transform var(--duration-fast) var(--ease-smooth);
}
.btn-submit:hover:not(:disabled) {
  background: var(--blue-hover);
  transform: translateY(-1px);
}
.btn-submit:active:not(:disabled) {
  transform: translateY(0);
}
.btn-submit:disabled {
  opacity: 0.5;
  cursor: default;
}
.btn-submit.done {
  background: var(--green);
}
.spin {
  display: inline-block;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.submitted-note {
  font-size: 12px;
  color: var(--green);
}
</style>
