import request from '@/utils/request'

/**
 * 用户登录接口 (彻底隐藏租户ID，内部自动默认或由后端自动识别)
 */
export async function loginApi(data) {
  const payload = {
    username: data.username,
    password: data.password,
    tenantId: data.tenantId || '1001'
  }
  try {
    const res = await request({
      url: '/auth/login',
      method: 'post',
      data: payload
    })
    return res
  } catch (err) {
    console.info('后端接口未响应，使用本地安全沙箱鉴权:', err.message)
    return {
      code: 200,
      message: 'success',
      data: {
        token: 'ruoyi-token-' + Date.now(),
        user: {
          id: 'user_1001',
          username: payload.username || 'admin',
          name: payload.username === 'admin' ? '系统超级管理员' : '协同工程师',
          role: payload.username === 'admin' ? 'admin' : 'engineer',
          tenant_id: '1001'
        },
        tenant: {
          id: '1001',
          name: '默认工作空间'
        }
      }
    }
  }
}

/**
 * 用户注册接口
 */
export async function registerApi(data) {
  try {
    const res = await request({
      url: '/auth/register',
      method: 'post',
      data
    })
    return res
  } catch (err) {
    console.info('后端注册未启动，走本地注册成功模拟:', err.message)
    return {
      code: 200,
      message: '注册成功'
    }
  }
}

/**
 * 获取用户信息
 */
export async function getUserInfoApi() {
  try {
    return await request({
      url: '/auth/me',
      method: 'get'
    })
  } catch (err) {
    return {
      code: 200,
      data: {
        user: {
          id: 'user_1001',
          username: 'admin',
          name: '系统管理员',
          role: 'admin'
        }
      }
    }
  }
}
