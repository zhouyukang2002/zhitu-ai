import { request } from './request'

export const fetchSessions = () => request('/api/session/list')

export const createSession = () => request('/api/session', { method: 'POST' })

export const fetchMessages = (id) => request(`/api/session/${id}/messages`)

export const fetchSessionState = (id) => request(`/api/session/${id}/state`)

export const deleteSession = (id) => request(`/api/session/${id}`, { method: 'DELETE' })

// 提交答案（后端按 exerciseId 幂等），返回 { grade, duplicated }
export const submitExercise = (sessionId, exerciseId, answers) =>
  request('/api/exercise/submit', { method: 'POST', body: { sessionId, exerciseId, answers } })

// 课程支付确认（后端按 orderId 幂等），返回 { order, duplicated }
export const payOrder = (orderId) =>
  request('/api/order/pay', { method: 'POST', body: { orderId } })
