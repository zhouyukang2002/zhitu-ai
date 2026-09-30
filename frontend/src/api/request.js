import { getAuthHeaders } from '../utils/auth'

// 统一 REST 请求封装：自动注入当前登录账号 Header (X-User-Id / X-User-Role)，解包 {code, message, data}
export async function request(url, { method = 'GET', body, signal, headers = {} } = {}) {
  const authHeaders = getAuthHeaders()

  const isFormData = typeof FormData !== 'undefined' && body instanceof FormData

  const mergedHeaders = {
    ...(body && !isFormData ? { 'Content-Type': 'application/json' } : {}),
    ...authHeaders,
    ...headers,
  }

  const resp = await fetch(url, {
    method,
    headers: mergedHeaders,
    body: isFormData ? body : (body ? JSON.stringify(body) : undefined),
    signal,
  })
  if (!resp.ok) {
    let message = `请求失败（${resp.status}）`
    if (resp.status === 403) {
      message = '权限拒绝：当前账号为学生身份，无权访问管理端研发看板'
    }
    try { message = (await resp.json()).message || message } catch { /* keep default */ }
    throw new Error(message)
  }
  const payload = await resp.json()
  if (payload.code !== 0) throw new Error(payload.message || '业务处理失败')
  return payload.data
}
