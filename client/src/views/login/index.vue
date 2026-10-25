<template>
  <div class="login">
    <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="login-form">
      <h3 class="title">TradeAgent 外贸智能体平台</h3>
      
      <!-- 账号 -->
      <el-form-item prop="username">
        <el-input
          v-model="loginForm.username"
          type="text"
          size="large"
          auto-complete="off"
          placeholder="账号"
          :prefix-icon="User"
        />
      </el-form-item>

      <!-- 密码 -->
      <el-form-item prop="password">
        <el-input
          v-model="loginForm.password"
          type="password"
          size="large"
          auto-complete="off"
          placeholder="密码"
          :prefix-icon="Lock"
          show-password
          @keyup.enter="handleLogin"
        />
      </el-form-item>

      <!-- 验证码 -->
      <el-form-item prop="code">
        <el-input
          v-model="loginForm.code"
          size="large"
          auto-complete="off"
          placeholder="验证码"
          style="width: 63%"
          :prefix-icon="Key"
          @keyup.enter="handleLogin"
        />
        <div class="login-code" title="点击切换验证码" @click="getCode">
          <canvas ref="captchaCanvasRef" class="login-code-img" width="115" height="40"></canvas>
        </div>
      </el-form-item>

      <!-- 记住密码 与 立即注册 -->
      <div class="login-options">
        <el-checkbox v-model="loginForm.rememberMe">记住密码</el-checkbox>
        <div class="register-link">
          <router-link class="link-type" to="/register">立即注册</router-link>
        </div>
      </div>

      <!-- 登录按钮 -->
      <el-form-item style="width:100%; margin-top: 15px;">
        <el-button
          :loading="loading"
          size="large"
          type="primary"
          style="width:100%;"
          @click.prevent="handleLogin"
        >
          <span v-if="!loading">登 录</span>
          <span v-else>登 录 中...</span>
        </el-button>
      </el-form-item>

      <!-- 飞书扫码登录 (SSO) -->
      <el-divider style="margin: 14px 0 10px">或</el-divider>
      <el-button
        size="large"
        style="width:100%;"
        plain
        :loading="feishuLoading"
        @click.prevent="feishuLogin"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" style="margin-right:6px;vertical-align:-4px;fill:#3370ff">
          <path d="M3.9 12c0-1.7 1.4-3.1 3.1-3.1s3.1 1.4 3.1 3.1-1.4 3.1-3.1 3.1S3.9 13.7 3.9 12zM13.9 5.5c0-1.7 1.4-3.1 3.1-3.1s3.1 1.4 3.1 3.1-1.4 3.1-3.1 3.1-3.1-1.4-3.1-3.1zM13.9 18.5c0-1.7 1.4-3.1 3.1-3.1s3.1 1.4 3.1 3.1-1.4 3.1-3.1 3.1-3.1-1.4-3.1-3.1zM6.1 12h2.2m1.8 0h1.2m-1.2 0 5.1-4.7m-5.1 9.4 5.1-4.7"/>
        </svg>
        <span v-if="!feishuLoading">飞书扫码登录</span>
        <span v-else>跳转飞书授权中...</span>
      </el-button>

      <div class="login-tip">
        <span>演示账号：admin / admin123</span>
      </div>
    </el-form>

    <!-- 底部版权 -->
    <div class="el-login-footer">
      <span>Copyright © 2026 TradeAgent All Rights Reserved.</span>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Key } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import request from '@/utils/request'

const router = useRouter()
const userStore = useUserStore()

const feishuLoading = ref(false)

// 飞书扫码 SSO: 跳转授权页, 回调带 code 回来换 JWT
const feishuLogin = async () => {
  feishuLoading.value = true
  try {
    const redirectUri = encodeURIComponent(window.location.origin + '/login?feishu=1')
    const res = await request({
      url: '/api/v1/feishu/auth/authorize-url',
      baseURL: '',
      method: 'get',
      params: { redirectUri }
    })
    if (res.code === 200 && res.data && res.data.authorizeUrl) {
      window.location.href = res.data.authorizeUrl
    } else {
      ElMessage.warning((res && res.message) || 'SSO 未配置或不可用，请用账号登录')
      feishuLoading.value = false
    }
  } catch (e) {
    ElMessage.error('获取飞书授权地址失败')
    feishuLoading.value = false
  }
}

// OAuth 回调: URL 带 ?feishu=1&code=xxx 时静默换 JWT 登录
const handleFeishuCallback = async () => {
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')
  if (!code || params.get('feishu') !== '1') return
  feishuLoading.value = true
  try {
    const res = await request({
      url: '/api/v1/feishu/auth/callback',
      baseURL: '',
      method: 'post',
      data: { code }
    })
    if (res.code === 200 && res.data && res.data.token) {
      userStore.setToken(res.data.token, { username: res.data.name })
      ElMessage.success('飞书登录成功: ' + res.data.name)
      router.push('/')
      return
    }
    ElMessage.warning((res && res.message) || '飞书授权码已失效，请重新扫码')
  } catch (e) {
    ElMessage.error('飞书登录失败: ' + (e.message || ''))
  } finally {
    feishuLoading.value = false
    // 清掉 URL 上的 code 参数防止刷新重复消费
    window.history.replaceState({}, '', '/login')
  }
}

const loginRef = ref(null)
const captchaCanvasRef = ref(null)
const loading = ref(false)
const correctCode = ref('')

const loginForm = reactive({
  username: 'admin',
  password: 'admin123',
  rememberMe: true,
  code: ''
})

const validateCode = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请输入验证码'))
  } else if (value.toLowerCase() !== correctCode.value.toLowerCase()) {
    callback(new Error('验证码错误'))
  } else {
    callback()
  }
}

const loginRules = {
  username: [{ required: true, trigger: 'blur', message: '请输入您的账号' }],
  password: [{ required: true, trigger: 'blur', message: '请输入您的密码' }],
  code: [{ required: true, trigger: 'change', validator: validateCode }]
}

// 模拟若依后端验证码生成
const getCode = () => {
  const chars = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'
  let code = ''
  for (let i = 0; i < 4; i++) {
    code += chars.charAt(Math.floor(Math.random() * chars.length))
  }
  correctCode.value = code
  loginForm.code = code // 默认自动填上，体验如丝般顺滑

  const canvas = captchaCanvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  ctx.clearRect(0, 0, 115, 40)

  // 浅色背景
  ctx.fillStyle = '#eef2f7'
  ctx.fillRect(0, 0, 115, 40)

  // 干扰线
  for (let i = 0; i < 3; i++) {
    ctx.strokeStyle = `rgb(${Math.random()*120+60}, ${Math.random()*120+60}, ${Math.random()*120+60})`
    ctx.beginPath()
    ctx.moveTo(Math.random() * 115, Math.random() * 40)
    ctx.lineTo(Math.random() * 115, Math.random() * 40)
    ctx.stroke()
  }

  // 字符
  for (let i = 0; i < code.length; i++) {
    ctx.font = 'bold 22px Arial'
    ctx.fillStyle = `rgb(${Math.random()*90+20}, ${Math.random()*90+20}, ${Math.random()*90+20})`
    ctx.textBaseline = 'middle'
    const x = 15 + i * 24
    const y = 20 + (Math.random() * 6 - 3)
    const angle = (Math.random() * 24 - 12) * Math.PI / 180
    ctx.save()
    ctx.translate(x, y)
    ctx.rotate(angle)
    ctx.fillText(code[i], 0, 0)
    ctx.restore()
  }
}

onMounted(() => {
  handleFeishuCallback()
  getCode()
})

const handleLogin = () => {
  loginRef.value.validate(async valid => {
    if (!valid) return
    loading.value = true
    try {
      await userStore.login({
        username: loginForm.username,
        password: loginForm.password
      })
      ElMessage.success('登录成功')
      router.push('/workbench')
    } catch (err) {
      ElMessage.error(err.message || '登录失败，请检查账号密码')
      getCode()
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped lang="scss">
.login {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  width: 100vw;
  background-color: #2d3a4b;
  background-image: linear-gradient(135deg, #1f2d3d 0%, #2d3a4b 50%, #3a4b5d 100%);
  position: relative;
  overflow: hidden;
}

.title {
  margin: 0px auto 30px auto;
  text-align: center;
  color: #707070;
  font-size: 20px;
  font-weight: 700;
}

.login-form {
  border-radius: 6px;
  background: #ffffff;
  width: 400px;
  padding: 25px 25px 15px 25px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.15);
  z-index: 10;

  .el-form-item {
    margin-bottom: 22px;
  }
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;

  .register-link {
    .link-type {
      color: #409eff;
      font-size: 13px;
      text-decoration: none;

      &:hover {
        color: #66b1ff;
      }
    }
  }
}

.login-tip {
  font-size: 12px;
  text-align: center;
  color: #909399;
  margin-top: 5px;
  margin-bottom: 5px;
}

.login-code {
  width: 33%;
  height: 40px;
  float: right;
  border-radius: 4px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid #dcdfe6;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #eef2f7;

  &:hover {
    border-color: #409eff;
  }
}

.login-code-img {
  height: 40px;
  width: 100%;
}

.el-login-footer {
  height: 40px;
  line-height: 40px;
  position: fixed;
  bottom: 0;
  width: 100%;
  text-align: center;
  color: #fff;
  font-family: Arial, sans-serif;
  font-size: 12px;
  letter-spacing: 1px;
}
</style>
