import { defineStore } from 'pinia'
import { loginApi } from '@/api/auth'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('tradeagent_token') || '',
    userInfo: JSON.parse(localStorage.getItem('tradeagent_user') || 'null'),
    tenant: JSON.parse(localStorage.getItem('tradeagent_tenant') || 'null')
  }),
  getters: {
    isLoggedIn: state => !!state.token,
    displayName: state => state.userInfo?.name || state.userInfo?.username || '用户'
  },
  actions: {
    async login(loginForm) {
      const res = await loginApi(loginForm)
      if (res.data) {
        this.token = res.data.token
        this.userInfo = res.data.user
        this.tenant = res.data.tenant

        localStorage.setItem('tradeagent_token', this.token)
        localStorage.setItem('tradeagent_user', JSON.stringify(this.userInfo))
        if (this.tenant) {
          localStorage.setItem('tradeagent_tenant', JSON.stringify(this.tenant))
        }
        return res.data
      }
      throw new Error(res.message || '登录失败')
    },
    logout() {
      this.token = ''
      this.userInfo = null
      this.tenant = null
      localStorage.removeItem('tradeagent_token')
      localStorage.removeItem('tradeagent_user')
      localStorage.removeItem('tradeagent_tenant')
    }
  }
})
