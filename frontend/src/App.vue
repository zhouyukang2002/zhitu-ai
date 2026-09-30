<template>
  <div class="app">
    <Sidebar />
    <ChatArea />
    <StatusPanel />
    <LoginModal />
  </div>
</template>

<script setup>
import Sidebar from './components/Sidebar.vue'
import ChatArea from './components/ChatArea.vue'
import StatusPanel from './components/StatusPanel.vue'
import LoginModal from './components/LoginModal.vue'
</script>

<style scoped>
.app {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr) 320px;
  /* 关键：锁定行高为视口高。会话再多也由侧栏内部滚动，
     否则隐式行随内容长高把文档撑到可滚动（输入框不固定、全页联动滚动的根因） */
  grid-template-rows: minmax(0, 1fr);
  height: 100vh;
  background: var(--bg);
}

/* 网格子项不允许把行撑高，滚动一律发生在各栏内部 */
.app > * {
  min-height: 0;
}

/* 响应式：窄屏隐藏左右栏，只保留对话区 */
@media (max-width: 1360px) {
  .app {
    grid-template-columns: 232px minmax(0, 1fr) 0;
  }
  .app > :nth-child(3) {
    display: none;
  }
}
@media (max-width: 920px) {
  .app {
    grid-template-columns: minmax(0, 1fr);
  }
  .app > :nth-child(1) {
    display: none;
  }
}
</style>
