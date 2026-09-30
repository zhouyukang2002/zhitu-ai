<template>
  <div class="order-card">
    <div class="card-head">
      <span class="card-icon-badge" :style="{ background: paid ? 'var(--green-soft)' : 'var(--blue-soft)', color: paid ? 'var(--green)' : 'var(--blue)' }">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
          <rect x="2.5" y="2" width="11" height="12" rx="1.5" stroke="currentColor" stroke-width="1.3"/>
          <path d="M5 5.5h6M5 8.5h6M5 11.5h3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
        </svg>
      </span>
      <span class="title">订单详情</span>
      <span class="subtitle">{{ orderId }}</span>
    </div>

    <div class="order-body">
      <div class="course-line">
        <div class="course-info">
          <div class="course-name">{{ courseName }}</div>
          <div class="course-id">课程编号 {{ courseId }}</div>
        </div>
        <div class="price">¥{{ price }}</div>
      </div>

      <div class="fee-row">
        <span class="fee-label">应付总额</span>
        <b class="fee-val">¥{{ price }}</b>
      </div>
    </div>

    <div class="order-foot">
      <template v-if="paid">
        <div class="paid-badge">
          <svg viewBox="0 0 14 14" width="13" height="13" fill="none">
            <path d="M2.5 7.4 5.7 10.5 11.5 3.8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          已完成支付
        </div>
        <span class="paid-tip">可在「学习路径」中开始学习</span>
      </template>
      <template v-else>
        <span class="pay-tip">点击确认完成支付（演示环境）</span>
        <button
          class="btn-pay"
          :class="{ loading: paying }"
          :disabled="paying"
          @click="pay"
        >
          <span v-if="paying" class="spin">⟳</span>
          {{ paying ? '支付处理中…' : '确认支付' }}
        </button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useChat } from '../../stores/chat'

const props = defineProps({ msg: { type: Object, required: true } })
const { doPay, payingIds } = useChat()

const orderId = computed(() => props.msg.content?.orderId)
const courseName = computed(() => props.msg.content?.courseName)
const courseId = computed(() => props.msg.content?.courseId)
const price = computed(() => props.msg.content?.price)
const paid = computed(() => props.msg.content?.status === 'PAID')
const paying = computed(() => !!payingIds[orderId.value])

async function pay() {
  await doPay(orderId.value)
}
</script>

<style scoped>
.order-body {
  padding: 4px 0 12px;
}
.course-line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: var(--surface-2);
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm);
}
.course-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-1);
}
.course-id {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 2px;
  font-family: var(--font-mono);
}
.price {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
}
.fee-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 4px 0;
}
.fee-label {
  font-size: 12.5px;
  color: var(--text-2);
}
.fee-val {
  font-size: 15px;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
}

.order-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--hairline);
}
.pay-tip {
  font-size: 11.5px;
  color: var(--text-3);
}
.paid-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--green);
  background: var(--green-soft);
  padding: 4px 10px;
  border-radius: var(--radius-sm);
}
.paid-tip {
  font-size: 11.5px;
  color: var(--text-3);
}
.btn-pay {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 18px;
  border-radius: 980px;
  border: none;
  background: var(--green);
  color: #fff;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-smooth),
              transform var(--duration-fast) var(--ease-smooth);
}
.btn-pay:hover:not(:disabled) {
  background: var(--green-hover);
  transform: translateY(-1px);
}
.btn-pay:active:not(:disabled) {
  transform: translateY(0);
}
.btn-pay:disabled {
  opacity: 0.5;
  cursor: default;
}
.spin {
  display: inline-block;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
