import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import AdminApp from './AdminApp.vue'
import '../styles/base.css'

// 研发看板独立入口：与用户端共享设计系统（tokens/base），不引入 vue-router
createApp(AdminApp).use(ElementPlus).mount('#app')
