import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(() => {
  return {
    plugins: [vue()],
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: {
        '/api': {
          target: process.env.VITE_BACKEND_URL || 'http://127.0.0.1:8080',
          changeOrigin: true,
        },
      },
    },
    build: {
      rollupOptions: {
        // 多页入口：/ 用户端 · /admin.html 研发看板（共享设计系统，不引入 vue-router）
        input: {
          main: 'index.html',
          admin: 'admin.html',
        },
      },
    },
  }
})
