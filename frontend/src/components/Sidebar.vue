<template>
  <aside class="sidebar">
    <!-- 品牌区 -->
    <div class="brand">
      <div class="logo">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none">
          <path d="M12 3C7 3 3 7 3 12s4 9 9 9 9-4 9-9S17 3 12 3Z" stroke="#fff" stroke-width="1.6"/>
          <path d="M8 12h8M12 8v8" stroke="#fff" stroke-width="1.6" stroke-linecap="round"/>
        </svg>
      </div>
      <div class="brand-text">
        <div class="name">智途 AI</div>
        <div class="slogan">AI Teaching Assistant</div>
      </div>
    </div>

    <!-- 新对话 -->
    <div class="new-btn-wrap">
      <button class="new-btn" :disabled="state.streaming" @click="newSession">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none">
          <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
        </svg>
        新对话
      </button>
    </div>

    <!-- 会话列表 -->
    <div class="session-list">
      <transition-group name="session" tag="div">
        <div
          v-for="s in state.sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: s.id === state.currentId }"
          @click="openSession(s.id)"
        >
          <div class="session-icon" :class="{ active: s.id === state.currentId }">
            <svg viewBox="0 0 16 16" width="12" height="12" fill="none">
              <path d="M2 3.5A1.5 1.5 0 0 1 3.5 2h9A1.5 1.5 0 0 1 14 3.5v7A1.5 1.5 0 0 1 12.5 12H9l-3 2v-2H3.5A1.5 1.5 0 0 1 2 10.5v-7Z" stroke="currentColor" stroke-width="1.2"/>
            </svg>
          </div>
          <div class="session-main">
            <div class="session-title">{{ s.title || '新的对话' }}</div>
            <div class="session-time">{{ timeAgo(s.updatedAt) }}</div>
          </div>
          <button class="del" title="删除会话" @click.stop="confirmDel(s)">
            <svg viewBox="0 0 16 16" width="12" height="12">
              <path d="M6 2.5h4M3.5 4.5h9M5 4.5l.5 8a1 1 0 0 0 1 .9h3a1 1 0 0 0 1-.9l.5-8"
                fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
            </svg>
          </button>
        </div>
      </transition-group>

      <div v-if="!state.sessions.length" class="empty-tip">
        <svg viewBox="0 0 32 32" width="28" height="28" fill="none" style="opacity:0.3;margin-bottom:8px">
          <path d="M4 7A3 3 0 0 1 7 4h18a3 3 0 0 1 3 3v14a3 3 0 0 1-3 3H18l-6 4v-4H7a3 3 0 0 1-3-3V7Z" stroke="currentColor" stroke-width="1.6"/>
        </svg>
        <div>还没有会话</div>
        <div>点击上方「新对话」开始</div>
      </div>
    </div>

    <!-- 底栏：当前登录用户与角色权限 -->
    <div class="sidebar-footer">
      <div v-if="authState.isLoggedIn && currentUser" class="user-profile-row">
        <div class="user-avatar" :class="{ 'admin-avatar': isAdmin }">
          {{ (currentUser.nickname || currentUser.username || 'U').charAt(0).toUpperCase() }}
        </div>
        <div class="user-info">
          <div class="user-name-line">
            <span class="user-nickname" :title="currentUser.nickname">{{ currentUser.nickname || currentUser.username }}</span>
            <span class="role-pill" :class="{ 'admin-pill': isAdmin }">
              {{ isAdmin ? '教研管理员' : '学员' }}
            </span>
          </div>
          <div class="user-sub-id">ID: {{ currentUser.userId }} · @{{ currentUser.username }}</div>
        </div>
        <button class="logout-btn" title="退出并切换账号" @click="handleLogout">
          <svg viewBox="0 0 16 16" width="12" height="12" fill="none">
            <path d="M6 2H3.5A1.5 1.5 0 0 0 2 3.5v9A1.5 1.5 0 0 0 3.5 14H6M10.5 11.5L14 8l-3.5-3.5M14 8H6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </div>

      <div v-else class="login-prompt-btn" @click="openLogin">
        <span>👉 点击登录 / 注册学员账号</span>
      </div>

      <!-- 仅管理员账号可见研发看板入口 -->
      <div v-if="isAdmin" class="admin-link-row">
        <a href="/admin.html" class="footer-link" title="进入研发与APM监控看板">
          <svg viewBox="0 0 16 16" width="12" height="12" fill="none">
            <path d="M7 3H3.5A1.5 1.5 0 0 0 2 4.5v8A1.5 1.5 0 0 0 3.5 14h8A1.5 1.5 0 0 0 13 12.5V9M10 2h4m0 0v4m0-4L8 8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          进入研发看板
        </a>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useChat } from '../stores/chat'
import { useAuth } from '../stores/auth'

const { state, openSession, newSession, removeSession, resetAndReload } = useChat()
const { state: authState, isAdmin, isStudent, currentUser, initAuth, logout, openLogin } = useAuth()

onMounted(async () => {
  initAuth()
  if (authState.isLoggedIn) {
    await resetAndReload()
  }
})

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
    logout()
    await resetAndReload()
    ElMessage.info('已退出登录')
  } catch { /* 用户取消 */ }
}

function timeAgo(ts) {
  const diff = Date.now() - ts
  if (diff < 60_000) return '刚刚'
  if (diff < 3600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

async function confirmDel(s) {
  try {
    await ElMessageBox.confirm(`确定删除「${s.title || '新的对话'}」吗？`, '删除会话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    removeSession(s.id)
  } catch { /* 用户取消 */ }
}
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  background: var(--surface-2);
  border-right: 0.5px solid var(--hairline);
  overflow: hidden;
}

/* ── 品牌 ── */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 16px 12px;
  flex: none;
}
.logo {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, #0969da, #054da7);
  display: grid;
  place-items: center;
  box-shadow: 0 2px 8px rgba(9, 105, 218, 0.24), 0 1px 2px rgba(0,0,0,0.08);
  flex: none;
}
.brand-text { min-width: 0; }
.name {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--text-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.slogan {
  font-size: 10px;
  color: var(--text-3);
  letter-spacing: 0.05em;
  margin-top: 1px;
  text-transform: uppercase;
}

/* ── 新对话按钮 ── */
.new-btn-wrap {
  padding: 0 12px 10px;
  flex: none;
}
.new-btn {
  width: 100%;
  height: 36px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--blue);
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: background 0.15s, transform 0.1s, box-shadow 0.15s;
  box-shadow: 0 1px 3px rgba(9, 105, 218, 0.24);
}
.new-btn:hover {
  background: var(--blue-hover);
  box-shadow: 0 2px 8px rgba(9, 105, 218, 0.28);
}
.new-btn:active { transform: scale(0.98); }
.new-btn:disabled { opacity: 0.45; cursor: default; box-shadow: none; }

/* ── 会话列表 ── */
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 2px 8px 12px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 8px 8px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-smooth);
  margin-bottom: 2px;
  border: 1px solid transparent;
}
.session-item:hover:not(.active) {
  background: rgba(31, 35, 40, 0.05);
}
.session-item.active {
  background: #edf5fe;
  border-color: rgba(9, 105, 218, 0.18);
  box-shadow: 0 1px 3px rgba(9, 105, 218, 0.06);
}
.session-icon {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: rgba(31, 35, 40, 0.05);
  display: grid;
  place-items: center;
  color: var(--text-3);
  transition: background 0.12s, color 0.12s;
}
.session-item.active .session-icon {
  background: rgba(9, 105, 218, 0.12);
  color: var(--blue);
}
.session-main { flex: 1; min-width: 0; }
.session-title {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-1);
  transition: color var(--duration-fast) ease;
}
.session-time {
  font-size: 10.5px;
  color: var(--text-3);
  margin-top: 2px;
}
.session-item.active .session-title {
  color: var(--blue);
  font-weight: 600;
}
.session-item.active .session-time {
  color: var(--text-2);
}

.del {
  flex: none;
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  border-radius: 6px;
  color: var(--text-3);
  cursor: pointer;
  display: grid;
  place-items: center;
  opacity: 0;
  transition: opacity 0.12s ease, background 0.12s ease, color 0.12s ease;
}
.session-item:hover .del { opacity: 1; }
.del:hover {
  background: rgba(207, 34, 46, 0.08);
  color: var(--red);
}
.session-item.active .del {
  color: var(--text-3);
}
.session-item.active .del:hover {
  background: rgba(207, 34, 46, 0.08);
  color: var(--red);
}

/* 空状态 */
.empty-tip {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 12px;
  font-size: 12px;
  color: var(--text-3);
  text-align: center;
  line-height: 1.8;
  gap: 0;
}

/* 列表过渡动画 */
.session-enter-active { transition: all 0.22s cubic-bezier(0.34, 1.2, 0.64, 1); }
.session-leave-active  { transition: all 0.15s ease; }
.session-enter-from { opacity: 0; transform: translateX(-12px); }
.session-leave-to   { opacity: 0; transform: translateX(-8px); }

/* ── 底栏 ── */
.sidebar-footer {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-top: 0.5px solid var(--hairline);
  background: var(--surface-2);
}

.user-profile-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 2px 0;
}

.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #0969da;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: grid;
  place-items: center;
  flex: none;
  box-shadow: 0 2px 6px rgba(9, 105, 218, 0.2);
}

.user-avatar.admin-avatar {
  background: #7c3aed;
  box-shadow: 0 2px 6px rgba(124, 58, 237, 0.25);
}

.user-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.user-name-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.user-nickname {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 110px;
}

.role-pill {
  font-size: 9.5px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(9, 105, 218, 0.1);
  color: var(--blue);
  flex: none;
}

.role-pill.admin-pill {
  background: rgba(124, 58, 237, 0.12);
  color: #7c3aed;
}

.user-sub-id {
  font-size: 10px;
  color: var(--text-3);
}

.logout-btn {
  border: none;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  transition: all 0.12s ease;
}

.logout-btn:hover {
  background: rgba(207, 34, 46, 0.1);
  color: #cf222e;
}

.login-prompt-btn {
  padding: 8px;
  border-radius: 8px;
  background: rgba(9, 105, 218, 0.08);
  color: var(--blue);
  font-size: 11.5px;
  font-weight: 600;
  text-align: center;
  cursor: pointer;
  transition: background 0.15s ease;
}

.login-prompt-btn:hover {
  background: rgba(9, 105, 218, 0.15);
}

.admin-link-row {
  border-top: 0.5px dashed var(--hairline);
  padding-top: 6px;
}

.footer-link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 11.5px;
  font-weight: 600;
  color: #7c3aed;
  background: rgba(124, 58, 237, 0.06);
  border: 1px solid rgba(124, 58, 237, 0.15);
  text-decoration: none;
  padding: 5px 8px;
  border-radius: 6px;
  transition: all 0.15s ease;
}

.footer-link:hover {
  background: #7c3aed;
  color: #fff;
}
</style>
