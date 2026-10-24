import { createRouter, createWebHistory } from 'vue-router'
import Layout from '@/layout/index.vue'

const routes = [
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '若依管理系统 - 登录' }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/register/index.vue'),
    meta: { title: '若依管理系统 - 注册' }
  },
  {
    path: '/system',
    component: Layout,
    redirect: '/workbench',
    children: [
      {
        path: '/workbench',
        name: 'Workbench',
        component: () => import('@/views/workbench/index.vue'),
        meta: { title: '工作台看板' }
      },
      {
        path: '/model-config',
        name: 'ModelConfig',
        component: () => import('@/views/model-config/index.vue'),
        meta: { title: 'AI 模型热插拔中心' }
      },
      {
        path: '/chat-agent',
        name: 'ChatAgent',
        component: () => import('@/views/chat-agent/index.vue'),
        meta: { title: '智能体对话工作台' }
      },
      {
        path: '/monitor',
        name: 'AgentMonitor',
        component: () => import('@/views/monitor/index.vue'),
        meta: { title: '执行监控' }
      },
      {
        path: '/feishu',
        name: 'FeishuApps',
        component: () => import('@/views/feishu/index.vue'),
        meta: { title: '飞书应用集成' }
      },

      {
        path: '/iot',
        name: 'IotMonitor',
        component: () => import('@/views/iot/index.vue'),
        meta: { title: 'IoT 设备接入' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - 若依管理系统` : '若依管理系统'
  const token = localStorage.getItem('tradeagent_token')
  if (to.path !== '/login' && to.path !== '/register' && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
