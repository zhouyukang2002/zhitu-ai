<template>
  <div v-if="state.showLoginModal" class="login-overlay">
    <div class="login-card">
      <!-- 头部 Brand -->
      <div class="card-brand">
        <div class="logo-box">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none">
            <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="#0969da" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <h1 class="brand-title">智能助教引擎</h1>
        <p class="brand-subtitle">职业技术与技能提升 · 多智能体自适应学习助教系统</p>
      </div>

      <!-- Tab 切换 -->
      <div class="tab-switcher">
        <button
          type="button"
          class="tab-item"
          :class="{ active: tab === 'login' }"
          @click="tab = 'login'"
        >
          账号登录
        </button>
        <button
          type="button"
          class="tab-item"
          :class="{ active: tab === 'register' }"
          @click="tab = 'register'"
        >
          学员注册
        </button>
      </div>

      <!-- 快捷演示通道 -->
      <div class="quick-demos">
        <div class="quick-title">⚡ 快速体验通道（一键填入）</div>
        <div class="demo-btn-group">
          <button type="button" class="demo-btn student" @click="fillDemo('student', '123456')">
            👨‍🎓 学员账号 (student)
          </button>
          <button type="button" class="demo-btn admin" @click="fillDemo('admin', 'admin123')">
            👨‍💻 管理员 (admin)
          </button>
        </div>
      </div>

      <!-- 登录表单 -->
      <form v-if="tab === 'login'" class="form-body" @submit.prevent="handleLogin">
        <div class="form-group">
          <label>用户名 / 账号</label>
          <input
            v-model="loginForm.username"
            type="text"
            placeholder="请输入用户名（如 student / admin）"
            required
            autocomplete="username"
          />
        </div>

        <div class="form-group">
          <label>密码</label>
          <input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入登录密码"
            required
            autocomplete="current-password"
          />
        </div>

        <button type="submit" class="submit-btn" :disabled="loading">
          <span v-if="loading" class="spinner" />
          <span>{{ loading ? '正在登录中...' : '进入职业技能学习系统' }}</span>
        </button>
      </form>

      <!-- 注册表单 -->
      <form v-else class="form-body" @submit.prevent="handleRegister">
        <div class="form-group">
          <label>用户名</label>
          <input
            v-model="regForm.username"
            type="text"
            placeholder="设置登录账号（如 zhangsan）"
            required
            autocomplete="username"
          />
        </div>

        <div class="form-group">
          <label>学员昵称 / 学习目标</label>
          <input
            v-model="regForm.nickname"
            type="text"
            placeholder="如：张同学 (零基础转Java后端 / 技能进阶)"
            autocomplete="nickname"
          />
        </div>

        <div class="form-group">
          <label>设置密码</label>
          <input
            v-model="regForm.password"
            type="password"
            placeholder="请输入 6 位以上密码"
            required
            autocomplete="new-password"
          />
        </div>

        <button type="submit" class="submit-btn" :disabled="loading">
          <span v-if="loading" class="spinner" />
          <span>{{ loading ? '正在注册并登录...' : '立即注册为学员并进入' }}</span>
        </button>
      </form>

      <!-- 底部版权/安全提示 -->
      <div class="card-footer-tip">
        <span>🔒 多用户数据物理隔离 · 严密保护学员答题与技能成长档案隐私</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'
import { useChat } from '../stores/chat'

const { state, login, register } = useAuth()
const { resetAndReload } = useChat()

const tab = ref('login')
const loading = ref(false)

const loginForm = reactive({
  username: 'student',
  password: '123456',
})

const regForm = reactive({
  username: '',
  nickname: '',
  password: '',
})

function fillDemo(u, p) {
  tab.value = 'login'
  loginForm.username = u
  loginForm.password = p
}

async function handleLogin() {
  if (loading.value) return
  loading.value = true
  try {
    const user = await login(loginForm.username, loginForm.password)
    ElMessage.success(`欢迎回来，${user.nickname}！`)
    await resetAndReload()
  } catch (e) {
    ElMessage.error(e.message || '登录失败，请检查账号密码')
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  if (loading.value) return
  loading.value = true
  try {
    const user = await register(regForm.username, regForm.password, regForm.nickname)
    ElMessage.success(`注册成功！已为您开启专属学员职业技能成长档案`)
    await resetAndReload()
  } catch (e) {
    ElMessage.error(e.message || '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  animation: fadeIn 0.25s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: scale(0.98); }
  to { opacity: 1; transform: scale(1); }
}

.login-card {
  width: 100%;
  max-width: 440px;
  background: #ffffff;
  border-radius: 20px;
  padding: 32px 28px;
  box-shadow: 0 24px 48px -12px rgba(15, 23, 42, 0.18), 0 0 0 1px rgba(0, 0, 0, 0.05);
  display: flex;
  flex-direction: column;
}

.card-brand {
  text-align: center;
  margin-bottom: 20px;
}

.logo-box {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: rgba(9, 105, 218, 0.08);
  display: grid;
  place-items: center;
  margin: 0 auto 12px;
}

.brand-title {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 4px;
  letter-spacing: -0.02em;
}

.brand-subtitle {
  font-size: 12.5px;
  color: #64748b;
  margin: 0;
}

/* Tab 切换 */
.tab-switcher {
  display: flex;
  background: #f1f5f9;
  border-radius: 10px;
  padding: 3px;
  margin-bottom: 16px;
}

.tab-item {
  flex: 1;
  border: none;
  background: transparent;
  padding: 8px 0;
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tab-item.active {
  background: #ffffff;
  color: #0f172a;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06);
}

/* 快速通道 */
.quick-demos {
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 12px;
  padding: 10px 12px;
  margin-bottom: 18px;
}

.quick-title {
  font-size: 11px;
  font-weight: 600;
  color: #475569;
  margin-bottom: 8px;
}

.demo-btn-group {
  display: flex;
  gap: 8px;
}

.demo-btn {
  flex: 1;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  border-radius: 8px;
  padding: 6px 4px;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  text-align: center;
}

.demo-btn.student {
  color: #0969da;
}
.demo-btn.student:hover {
  background: rgba(9, 105, 218, 0.08);
  border-color: #0969da;
}

.demo-btn.admin {
  color: #7c3aed;
}
.demo-btn.admin:hover {
  background: rgba(124, 58, 237, 0.08);
  border-color: #7c3aed;
}

/* 表单主体 */
.form-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  text-align: left;
}

.form-group label {
  font-size: 12px;
  font-weight: 600;
  color: #334155;
}

.form-group input {
  height: 40px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid #cbd5e1;
  font-size: 13.5px;
  color: #0f172a;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.form-group input:focus {
  border-color: #0969da;
  box-shadow: 0 0 0 3px rgba(9, 105, 218, 0.12);
}

.submit-btn {
  margin-top: 6px;
  height: 42px;
  border: none;
  border-radius: 10px;
  background: #0969da;
  color: #ffffff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: background 0.15s ease, transform 0.1s;
}

.submit-btn:hover:not(:disabled) {
  background: #054da7;
  transform: translateY(-1px);
}

.submit-btn:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid #ffffff;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.card-footer-tip {
  margin-top: 18px;
  text-align: center;
  font-size: 11px;
  color: #94a3b8;
}
</style>
