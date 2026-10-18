import axios from 'axios'
import { ElMessage } from 'element-plus'

const service = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器
service.interceptors.request.use(
  config => {
    const token = localStorage.getItem('tradeagent_token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器
service.interceptors.response.use(
  response => {
    const res = response.data
    // 如果返回的结构包含 code
    if (res.code !== undefined && res.code !== 200) {
      ElMessage.error(res.message || '请求处理异常')
      return Promise.reject(new Error(res.message || 'Error'))
    }
    return res
  },
  error => {
    // 捕获网络异常
    const msg = error.response?.data?.message || error.message || '网关连接超时或未启动'
    console.warn('API Request Warning:', msg)
    return Promise.reject(error)
  }
)

export default service
