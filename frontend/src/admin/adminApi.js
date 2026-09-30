import { request } from '../api/request'

// 研发看板 API（后端 ObservationController /api/admin/*）
export const fetchMetrics = (days = 7) => request(`/api/admin/metrics?days=${days}`)

export const fetchTimeseries = (days = 14) => request(`/api/admin/metrics/timeseries?days=${days}`)

export const fetchTraces = ({ sessionId = '', query = '', intent = '', status = '', date = '', page = 1, size = 50 } = {}) => {
  const p = new URLSearchParams()
  if (sessionId) p.set('sessionId', sessionId)
  if (query) p.set('query', query)
  if (intent) p.set('intent', intent)
  if (status) p.set('status', status)
  if (date) p.set('date', date)
  p.set('page', page)
  p.set('size', size)
  return request(`/api/admin/traces?${p}`)
}

export const fetchTrace = (id) => request(`/api/admin/trace/${id}`)

export const saveLabel = (id, expectedIntent) =>
  request(`/api/admin/trace/${id}/label`, { method: 'POST', body: { expectedIntent } })

export const saveFeedback = (traceId, rating) =>
  request('/api/admin/feedback', { method: 'POST', body: { traceId, rating } })

export const runEval = ({ days = 7, includeJudge = true, limit } = {}) =>
  request('/api/admin/eval', {
    method: 'POST',
    body: {
      startAt: new Date(Date.now() - days * 86400000).toISOString(),
      endAt: new Date().toISOString(),
      includeJudge,
      limit,
    },
  })

// 知识库全生命周期治理与 RAG 评测 API
export const fetchKnowledgeOverview = () => request('/api/admin/knowledge/overview')

export const fetchKnowledgeDocs = ({ category = 'ALL', keyword = '', page = 1, size = 10 } = {}) =>
  request(`/api/admin/knowledge/list?${new URLSearchParams({ category, keyword, page, size })}`)

export const fetchKnowledgeDetail = (docId, category = '') =>
  request(`/api/admin/knowledge/detail?${new URLSearchParams({ docId, category })}`)

export const uploadKnowledgeDoc = (formData) =>
  request('/api/admin/knowledge/upload', { method: 'POST', body: formData })

export const deleteKnowledgeDoc = (docId, category = '') =>
  request(`/api/admin/knowledge/delete?${new URLSearchParams({ docId, category })}`, { method: 'DELETE' })

export const fetchRagMetrics = () => request('/api/admin/knowledge/rag-metrics')

export const searchRetrieval = ({ query, kp = '', topK = 5, rerank = true }) =>
  request(`/api/admin/retrieval/search?${new URLSearchParams({ query, kp, topK, rerank })}`)

export const syncAllCorpus = () => request('/api/admin/corpus/sync', { method: 'POST' })

