<template>
  <div class="register">
    <el-form ref="registerRef" :model="registerForm" :rules="registerRules" class="register-form">
      <h3 class="title">若依管理系统</h3>
      
      <!-- 账号 -->
      <el-form-item prop="username">
        <el-input
          v-model="registerForm.username"
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
          v-model="registerForm.password"
          type="password"
          size="large"
          auto-complete="off"
          placeholder="密码"
          :prefix-icon="Lock"
          show-password
        />
      </el-form-item>

      <!-- 确认密码 -->
      <el-form-item prop="confirmPassword">
        <el-input
          v-model="registerForm.confirmPassword"
          type="password"
          size="large"
          auto-complete="off"
          placeholder="确认密码"
          :prefix-icon="Lock"
          show-password
          @keyup.enter="handleRegister"
        />
      </el-form-item>

      <!-- 验证码 -->
      <el-form-item prop="code">
        <el-input
          v-model="registerForm.code"
          size="large"
          auto-complete="off"
          placeholder="验证码"
          style="width: 63%"
          :prefix-icon="Key"
          @keyup.enter="handleRegister"
        />
        <div class="register-code" title="点击切换验证码" @click="getCode">
          <canvas ref="captchaCanvasRef" class="register-code-img" width="115" height="40"></canvas>
        </div>
      </el-form-item>

      <!-- 注册按钮 -->
      <el-form-item style="width:100%; margin-top: 10px;">
        <el-button
          :loading="loading"
          size="large"
          type="primary"
          style="width:100%;"
          @click.prevent="handleRegister"
        >
          <span v-if="!loading">注 册</span>
          <span v-else>注 册 中...</span>
        </el-button>
        <div style="float: right; margin-top: 10px;">
          <router-link class="link-type" to="/login">使用已有账号登录</router-link>
        </div>
      </el-form-item>
    </el-form>

    <!-- 底部版权 -->
    <div class="el-register-footer">
      <span>Copyright © 2018-2026 ruoyi.vip All Rights Reserved.</span>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Key } from '@element-plus/icons-vue'
import { registerApi } from '@/api/auth'

const router = useRouter()
const registerRef = ref(null)
const captchaCanvasRef = ref(null)
const loading = ref(false)
const correctCode = ref('')

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  code: ''
})

const equalToPassword = (rule, value, callback) => {
  if (registerForm.password !== value) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const validateCode = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请输入验证码'))
  } else if (value.toLowerCase() !== correctCode.value.toLowerCase()) {
    callback(new Error('验证码错误'))
  } else {
    callback()
  }
}

const registerRules = {
  username: [
    { required: true, trigger: 'blur', message: '请输入您的账号' },
    { min: 2, max: 20, message: '用户账号长度必须介于 2 和 20 之间', trigger: 'blur' }
  ],
  password: [
    { required: true, trigger: 'blur', message: '请输入您的密码' },
    { min: 5, max: 20, message: '用户密码长度必须介于 5 和 20 之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, trigger: 'blur', message: '请再次输入您的密码' },
    { validator: equalToPassword, trigger: 'blur' }
  ],
  code: [{ required: true, trigger: 'change', validator: validateCode }]
}

const getCode = () => {
  const chars = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'
  let code = ''
  for (let i = 0; i < 4; i++) {
    code += chars.charAt(Math.floor(Math.random() * chars.length))
  }
  correctCode.value = code
  registerForm.code = code

  const canvas = captchaCanvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  ctx.clearRect(0, 0, 115, 40)
  ctx.fillStyle = '#eef2f7'
  ctx.fillRect(0, 0, 115, 40)

  for (let i = 0; i < 3; i++) {
    ctx.strokeStyle = `rgb(${Math.random()*120+60}, ${Math.random()*120+60}, ${Math.random()*120+60})`
    ctx.beginPath()
    ctx.moveTo(Math.random() * 115, Math.random() * 40)
    ctx.lineTo(Math.random() * 115, Math.random() * 40)
    ctx.stroke()
  }

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
  getCode()
})

const handleRegister = () => {
  registerRef.value.validate(async valid => {
    if (!valid) return
    loading.value = true
    try {
      await registerApi({
        username: registerForm.username,
        password: registerForm.password
      })
      ElMessage.success('恭喜您，账号注册成功！')
      router.push('/login')
    } catch (err) {
      ElMessage.error(err.message || '注册失败')
      getCode()
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped lang="scss">
.register {
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

.register-form {
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

.link-type {
  color: #409eff;
  font-size: 13px;
  text-decoration: none;

  &:hover {
    color: #66b1ff;
  }
}

.register-code {
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

.register-code-img {
  height: 40px;
  width: 100%;
}

.el-register-footer {
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
