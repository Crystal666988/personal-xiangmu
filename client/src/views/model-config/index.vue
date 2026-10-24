<template>
  <div class="model-config-container">
    <!-- 顶部状态卡（全部来自真实数据：本地保存的配置 + 真实探针结果） -->
    <el-row :gutter="16" class="metrics-row">
      <el-col :xs="24" :sm="12" :md="12">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-header">
            <span class="metric-title">当前保存的模型配置</span>
            <el-tag size="small" :type="hasSavedConfig ? 'success' : 'info'" effect="dark">
              {{ hasSavedConfig ? '已生效' : '未保存' }}
            </el-tag>
          </div>
          <div class="metric-value">{{ modelConfigForm.modelName || '--' }}</div>
          <div class="metric-footer">
            <span class="label">接入端点:</span>
            <span class="value font-mono text-truncate">{{ modelConfigForm.baseUrl || '--' }}</span>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="12">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-header">
            <span class="metric-title">连接探针延迟 (1-Token 真实握手)</span>
            <el-tag size="small" :type="probeData.status === 'HEALTHY' ? 'success' : probeData.status === 'FAILED' ? 'danger' : 'info'" effect="plain">
              {{ probeData.status === 'HEALTHY' ? '健康' : probeData.status === 'FAILED' ? '失败' : '待探测' }}
            </el-tag>
          </div>
          <div class="metric-value" :class="probeData.status === 'HEALTHY' ? 'text-success' : ''">
            {{ probeData.latencyMs ? probeData.latencyMs + ' ms' : '--' }}
          </div>
          <div class="metric-footer">
            <span class="label">最近探测:</span>
            <span class="value">{{ probeData.rateDate || '尚未探测' }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 主面板：模型参数与 API Key 配置（标准管理系统式手动保存） -->
    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text">
            <el-icon><EditPen /></el-icon> 模型参数与 API Key 配置
          </span>
          <el-tag size="small" type="info" effect="plain">保存后对话即时使用</el-tag>
        </div>
      </template>

      <el-form
        ref="modelFormRef"
        :model="modelConfigForm"
        label-width="130px"
        size="default"
        class="config-form"
      >
        <el-form-item label="模型提供商">
          <el-select
            v-model="modelConfigForm.provider"
            placeholder="请选择模型提供商"
            style="width: 100%"
            @change="handleProviderChange"
          >
            <el-option label="DeepSeek 官方 API" value="deepseek" />
            <el-option label="阿里云 DashScope (通义千问)" value="aliyun" />
            <el-option label="OpenAI 官方端点" value="openai" />
            <el-option label="本地私有化部署 (Ollama / vLLM)" value="custom" />
          </el-select>
        </el-form-item>

        <el-form-item label="接入 BaseURL">
          <el-input
            v-model="modelConfigForm.baseUrl"
            placeholder="例如: https://api.deepseek.com/v1"
            clearable
          />
        </el-form-item>

        <el-form-item label="API Key 密钥">
          <el-input
            v-model="modelConfigForm.apiKey"
            type="password"
            show-password
            placeholder="请输入您自己的 API 密钥 (sk-xxx，仅保存在本机浏览器)"
          />
          <div class="form-tip">
            密钥只保存在本机浏览器 localStorage，随请求传给后端创建本次对话模型；不会入库，也不会下发给其他用户。
          </div>
        </el-form-item>

        <el-form-item label="具体模型名称">
          <el-select
            v-model="modelConfigForm.modelName"
            filterable
            allow-create
            default-first-option
            placeholder="请选择或输入模型名称"
            style="width: 100%"
          >
            <el-option-group v-if="modelConfigForm.provider === 'aliyun'" label="阿里云通义千问">
              <el-option label="qwen-plus (文本旗舰)" value="qwen-plus" />
              <el-option label="qwen-max (深度推理旗舰)" value="qwen-max" />
              <el-option label="qwen3-vl-plus (全模态视觉)" value="qwen3-vl-plus" />
              <el-option label="qwen-vl-ocr (高精度单据 OCR)" value="qwen-vl-ocr" />
            </el-option-group>
            <el-option-group v-else-if="modelConfigForm.provider === 'deepseek'" label="DeepSeek 官方模型">
              <el-option label="deepseek-chat (V3 纯文本旗舰)" value="deepseek-chat" />
              <el-option label="deepseek-reasoner (R1 深度思考链)" value="deepseek-reasoner" />
            </el-option-group>
            <el-option-group v-else label="常用通用模型">
              <el-option label="gpt-4o (全模态视觉+文本)" value="gpt-4o" />
              <el-option label="gpt-4o-mini (轻量视觉+文本)" value="gpt-4o-mini" />
            </el-option-group>
          </el-select>
        </el-form-item>

        <el-form-item label="温度参数 (Temp)">
          <div class="slider-block">
            <el-slider
              v-model="modelConfigForm.temperature"
              :min="0.0"
              :max="2.0"
              :step="0.1"
              show-input
            />
          </div>
        </el-form-item>

        <el-form-item label="最大输出 Token">
          <div class="slider-block">
            <el-slider
              v-model="modelConfigForm.maxTokens"
              :min="1024"
              :max="8192"
              :step="512"
              show-input
            />
          </div>
        </el-form-item>

        <!-- 连接探针：真实 1-Token 测试，只展示真实返回 -->
        <el-form-item label="连接探针">
          <el-button
            type="primary"
            plain
            :loading="probing"
            @click="runConnectivityProbe"
          >
            <el-icon><Odometer /></el-icon> 测试连通性 (1-Token 真实请求)
          </el-button>
          <div v-if="probeData.status !== 'READY' || probeData.error" class="probe-result font-mono">
            <p>
              状态:
              <span :class="probeData.status === 'HEALTHY' ? 'ok' : probeData.status === 'FAILED' ? 'bad' : ''">
                {{ probeData.status }}
              </span>
              <template v-if="probeData.latencyMs">｜延迟: {{ probeData.latencyMs }} ms</template>
            </p>
            <p v-if="probeData.error" class="bad">错误: {{ probeData.error }}</p>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="saving"
            @click="handleSaveModelConfig"
          >
            保存并生效
          </el-button>
          <el-button size="large" @click="resetForm">重 置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import {
  Odometer,
  EditPen
} from '@element-plus/icons-vue'
import {
  probeModelApi,
  saveModelConfigApi
} from '@/api/model'

const probing = ref(false)
const saving = ref(false)
const hasSavedConfig = ref(false)

const probeData = reactive({
  status: 'READY',
  latencyMs: null,
  error: '',
  rateDate: ''
})

const modelConfigForm = reactive({
  provider: 'deepseek',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKey: '',
  modelName: 'deepseek-chat',
  temperature: 0.7,
  maxTokens: 4096
})

onMounted(() => {
  const savedConfig = localStorage.getItem('tradeagent_model_config')
  if (savedConfig) {
    try {
      const parsed = JSON.parse(savedConfig)
      Object.assign(modelConfigForm, parsed)
      hasSavedConfig.value = Boolean(parsed.apiKey)
    } catch (e) {
      console.warn('Failed to parse saved model config', e)
    }
  }
})

// 真实 1-Token 连通性探针（后端真实调用一次模型端点；只展示后端真实返回）
const runConnectivityProbe = async () => {
  if (!modelConfigForm.apiKey || !modelConfigForm.apiKey.trim()) {
    ElMessage.warning('请先填写 API Key 再发起连通性测试')
    return
  }

  probing.value = true
  probeData.status = 'PROBING'
  probeData.error = ''
  probeData.latencyMs = null
  probeData.rateDate = ''

  try {
    const res = await probeModelApi({
      tenantId: '1001',
      modelName: modelConfigForm.modelName,
      baseUrl: modelConfigForm.baseUrl,
      apiKey: modelConfigForm.apiKey,
      provider: modelConfigForm.provider
    })

    if (res.data) {
      probeData.status = 'HEALTHY'
      probeData.latencyMs = res.data.latencyMs
      probeData.rateDate = new Date().toLocaleTimeString()
      ElMessage.success(`连通性正常，真实往返延迟 ${res.data.latencyMs}ms`)
    }
  } catch (err) {
    probeData.status = 'FAILED'
    probeData.latencyMs = null
    probeData.error = err.message || '端点响应异常'
  } finally {
    probing.value = false
  }
}

// 提供商变动快捷更新 BaseURL
const handleProviderChange = (val) => {
  if (val === 'deepseek') {
    modelConfigForm.baseUrl = 'https://api.deepseek.com/v1'
    modelConfigForm.modelName = 'deepseek-chat'
  } else if (val === 'aliyun') {
    modelConfigForm.baseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
    modelConfigForm.modelName = 'qwen-plus'
  } else if (val === 'openai') {
    modelConfigForm.baseUrl = 'https://api.openai.com/v1'
    modelConfigForm.modelName = 'gpt-4o'
  } else {
    modelConfigForm.baseUrl = 'http://127.0.0.1:11434/v1'
    modelConfigForm.modelName = 'qwen2.5:7b'
  }
}

// 保存配置：本地持久化（对话页与后端 SSE 均以这份配置为准）
const handleSaveModelConfig = async () => {
  saving.value = true
  const payload = {
    tenantId: '1001',
    ...modelConfigForm
  }
  localStorage.setItem('tradeagent_model_config', JSON.stringify(payload))
  hasSavedConfig.value = Boolean(payload.apiKey)

  try {
    await saveModelConfigApi(payload)
    ElMessage.success('模型配置已保存并生效')
  } catch (err) {
    ElMessage.success('模型配置已在本机保存并生效')
  } finally {
    saving.value = false
  }
}

const resetForm = () => {
  handleProviderChange(modelConfigForm.provider)
  modelConfigForm.temperature = 0.7
  modelConfigForm.maxTokens = 4096
  ElMessage.info('已恢复该提供商的默认参数')
}
</script>

<style scoped lang="scss">
.model-config-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.metrics-row {
  margin-bottom: 2px;

  .metric-card {
    border-radius: 6px;
    border: 1px solid #e4e7ed;

    .metric-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;

      .metric-title {
        font-size: 13px;
        color: #909399;
      }
    }

    .metric-value {
      font-size: 22px;
      font-weight: 700;
      color: #303133;
      margin-bottom: 10px;

      &.text-success { color: #67c23a; }
    }

    .metric-footer {
      font-size: 12px;
      color: #909399;
      display: flex;
      align-items: center;
      gap: 6px;
      overflow: hidden;

      .value {
        color: #606266;
      }

      .text-truncate {
        max-width: 380px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }
}

.box-card {
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  background: #ffffff;

  .card-title-bar {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .title-text {
      font-size: 15px;
      font-weight: 600;
      color: #303133;
      display: flex;
      align-items: center;
      gap: 6px;
    }
  }
}

.config-form {
  max-width: 860px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin-top: 4px;
  width: 100%;
}

.probe-result {
  width: 100%;
  margin-top: 10px;
  background: #f8fafc;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12.5px;

  p {
    margin: 2px 0;
  }

  .ok { color: #67c23a; font-weight: 600; }
  .bad { color: #e06c75; font-weight: 600; }
}

.slider-block {
  width: 100%;
}

.font-mono {
  font-family: Consolas, Monaco, "Courier New", monospace;
}
</style>
