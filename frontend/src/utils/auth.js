export const STORAGE_KEY = 'tutor_current_user'

export function getStoredUser() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function getAuthHeaders(fallbackUserId = null) {
  const user = getStoredUser()
  if (user) {
    return {
      'X-User-Id': String(user.userId || fallbackUserId || 1001),
      'X-User-Role': user.role || 'ROLE_USER',
      'X-User-Name': encodeURIComponent(user.username || 'student'),
    }
  }
  if (fallbackUserId) {
    return {
      'X-User-Id': String(fallbackUserId),
      'X-User-Role': 'ROLE_USER',
    }
  }
  return {}
}
