import { reactive, computed } from 'vue'
import { request } from '../api/request'
import { STORAGE_KEY, getStoredUser, getAuthHeaders } from '../utils/auth'

export { getAuthHeaders }

const defaultUser = getStoredUser() || {
  userId: 1001,
  username: 'student',
  nickname: '张同学 (非计算机转Java后端)',
  role: 'ROLE_USER',
  isAdmin: false,
}

const state = reactive({
  user: defaultUser,
  isLoggedIn: !!getStoredUser(),
  showLoginModal: !getStoredUser(),
})

const isAdmin = computed(() => state.user?.role === 'ROLE_ADMIN' || state.user?.isAdmin === true)
const isStudent = computed(() => !isAdmin.value)
const currentUser = computed(() => state.user)

export function useAuth() {
  function initAuth() {
    const stored = getStoredUser()
    if (stored) {
      state.user = stored
      state.isLoggedIn = true
      state.showLoginModal = false
    } else {
      state.user = null
      state.isLoggedIn = false
      state.showLoginModal = true
    }
  }

  async function login(username, password) {
    const res = await request('/api/auth/login', {
      method: 'POST',
      body: { username, password }
    })
    state.user = {
      userId: res.userId,
      username: res.username,
      nickname: res.nickname || res.username,
      role: res.role,
      isAdmin: res.isAdmin === true || res.role === 'ROLE_ADMIN',
    }
    state.isLoggedIn = true
    state.showLoginModal = false
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state.user))
    return state.user
  }

  async function register(username, password, nickname) {
    const res = await request('/api/auth/register', {
      method: 'POST',
      body: { username, password, nickname }
    })
    // 注册成功后自动登录为学生
    state.user = {
      userId: res.userId,
      username: res.username,
      nickname: res.nickname || res.username,
      role: res.role,
      isAdmin: false,
    }
    state.isLoggedIn = true
    state.showLoginModal = false
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state.user))
    return state.user
  }

  function logout() {
    localStorage.removeItem(STORAGE_KEY)
    state.user = null
    state.isLoggedIn = false
    state.showLoginModal = true
  }

  function openLogin() {
    state.showLoginModal = true
  }

  function closeLogin() {
    if (state.isLoggedIn) {
      state.showLoginModal = false
    }
  }

  return {
    state,
    isAdmin,
    isStudent,
    currentUser,
    initAuth,
    login,
    register,
    logout,
    openLogin,
    closeLogin,
  }
}
