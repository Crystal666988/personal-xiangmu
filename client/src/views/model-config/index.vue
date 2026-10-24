<template>
  <div class="model-config-container">
    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text">
            <el-icon><EditPen /></el-icon> 模型配置管理
          </span>
          <div class="title-actions">
            <el-tag size="small" type="info" effect="plain">一份配置 = 提供商 + 端点 + 密钥 + 模型</el-tag>
            <el-button type="primary" size="default" @click="handleAdd">
              <el-icon><Plus /></el-icon> 新增配置
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="configList" style="width: 100%" size="default">
        <el-table-column prop="name" label="配置名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="name-text">{{ row.name }}</span>
            <el-tag v-if="row.active" size="small" type="success" effect="dark" class="ml-2">使用中</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="provider" label="提供商" width="150">
          <template #default="{ row }">{{ providerLabel(row.provider) }}</template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="接入 BaseURL" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="font-mono">{{ row.baseUrl }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型名称" width="150" />
        <el-table-column label="API Key" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.apiKey ? 'success' : 'info'" effect="plain">
              {{ row.apiKey ? '已配置' : '未配置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="temperature" label="温度" width="70" />
        <el-table-column prop="maxTokens" label="MaxToken" width="90" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="!row.active"
              size="small"
              type="success"
              link
              @click="handleEnable(row)"
            >
              启用
            </el-button>
            <el-button size="small" type="primary" link @click="handleEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" link @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有模型配置，点击右上角「新增配置」创建第一份" />
        </template>
      </el-table>

      <div class="storage-tip">
        所有配置仅保存在本机浏览器 localStorage；「使用中」的那份会在对话与后端流式生成时使用。API Key 不会入库、不会下发给其他用户。
      </div>
    </el-card>

    <!-- 新增 / 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'add' ? '新增模型配置' : '编辑模型配置'"
      width="640px"
      destroy-on-close
    >
      <el-form
        ref="dialogFormRef"
        :model="dialogForm"
        :rules="dialogRules"
        label-width="130px"
        size="default"
      >
        <el-form-item label="配置名称" prop="name">
          <el-input v-model="dialogForm.name" placeholder="例如: 通义千问-生产 / DeepSeek-备用" maxlength="40" />
        </el-form-item>

        <el-form-item label="模型提供商" prop="provider">
          <el-select
            v-model="dialogForm.provider"
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

        <el-form-item label="接入 BaseURL" prop="baseUrl">
          <el-input v-model="dialogForm.baseUrl" placeholder="例如: https://api.deepseek.com/v1" clearable />
        </el-form-item>

        <el-form-item label="API Key 密钥" prop="apiKey">
          <el-input
            v-model="dialogForm.apiKey"
            type="password"
            show-password
            placeholder="sk-xxx；仅保存在本机浏览器 localStorage"
          />
        </el-form-item>

        <el-form-item label="具体模型名称" prop="modelName">
          <el-select
            v-model="dialogForm.modelName"
            filterable
            allow-create
            default-first-option
            placeholder="请选择或输入模型名称"
            style="width: 100%"
          >
            <el-option-group v-if="dialogForm.provider === 'aliyun'" label="阿里云通义千问">
              <el-option label="qwen-plus (文本旗舰)" value="qwen-plus" />
              <el-option label="qwen-max (深度推理旗舰)" value="qwen-max" />
              <el-option label="qwen3-vl-plus (全模态视觉)" value="qwen3-vl-plus" />
              <el-option label="qwen-vl-ocr (高精度单据 OCR)" value="qwen-vl-ocr" />
            </el-option-group>
            <el-option-group v-else-if="dialogForm.provider === 'deepseek'" label="DeepSeek 官方模型">
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
            <el-slider v-model="dialogForm.temperature" :min="0.0" :max="2.0" :step="0.1" show-input />
          </div>
        </el-form-item>

        <el-form-item label="最大输出 Token">
          <div class="slider-block">
            <el-slider v-model="dialogForm.maxTokens" :min="1024" :max="8192" :step="512" show-input />
          </div>
        </el-form-item>

        <el-form-item label="连通性测试">
          <el-button plain :loading="probing" @click="handleProbe">
            <el-icon><Odometer /></el-icon> 测试连通性 (1-Token 真实请求)
          </el-button>
          <div v-if="probeResult" class="probe-result font-mono">
            <p>
              状态:
              <span :class="probeResult.ok ? 'ok' : 'bad'">{{ probeResult.ok ? 'HEALTHY' : 'FAILED' }}</span>
              <template v-if="probeResult.ok">｜延迟: {{ probeResult.latencyMs }} ms</template>
            </p>
            <p v-if="probeResult.error" class="bad">错误: {{ probeResult.error }}</p>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取 消</el-button>
        <el-button type="primary" :loading="saving" @click="handleDialogSave">保 存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Odometer, Plus } from '@element-plus/icons-vue'
import { probeModelApi } from '@/api/model'

const LIST_KEY = 'tradeagent_model_configs'
const LEGACY_KEY = 'tradeagent_model_config'

const configList = ref([])
const probing = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const dialogMode = ref('add')
const editingId = ref(null)
const probeResult = ref(null)

const dialogFormRef = ref(null)
const dialogForm = reactive({
  name: '',
  provider: 'deepseek',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKey: '',
  modelName: 'deepseek-chat',
  temperature: 0.7,
  maxTokens: 4096
})

const dialogRules = {
  name: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
  provider: [{ required: true, message: '请选择提供商', trigger: 'change' }],
  baseUrl: [{ required: true, message: '请输入接入 BaseURL', trigger: 'blur' }],
  modelName: [{ required: true, message: '请选择或输入模型名称', trigger: 'change' }]
}

const providerLabel = (p) => ({
  deepseek: 'DeepSeek',
  aliyun: '阿里云 DashScope',
  openai: 'OpenAI',
  custom: '本地私有化'
}[p] || p || '--')

// ---------- 存储层 ----------
const loadList = () => {
  try {
    const raw = localStorage.getItem(LIST_KEY)
    if (raw) return JSON.parse(raw)
  } catch (e) {
    console.warn('Failed to parse config list', e)
  }
  return null
}

const persist = (list) => {
  localStorage.setItem(LIST_KEY, JSON.stringify(list))
  const active = list.find(c => c.active)
  if (active) {
    // 同步"使用中"那份到旧键，供对话页与后端 SSE 直接消费
    localStorage.setItem(LEGACY_KEY, JSON.stringify({
      tenantId: '1001',
      provider: active.provider,
      baseUrl: active.baseUrl,
      apiKey: active.apiKey,
      modelName: active.modelName,
      temperature: active.temperature,
      maxTokens: active.maxTokens
    }))
  }
}

onMounted(() => {
  let list = loadList()
  if (!list) {
    // 迁移旧的单份配置为列表中的"使用中"条目
    try {
      const legacyRaw = localStorage.getItem(LEGACY_KEY)
      if (legacyRaw) {
        const legacy = JSON.parse(legacyRaw)
        list = [{
          id: 'cfg_' + Date.now(),
          name: (legacy.modelName || '默认模型') + ' 配置',
          provider: legacy.provider,
          baseUrl: legacy.baseUrl,
          apiKey: legacy.apiKey,
          modelName: legacy.modelName,
          temperature: legacy.temperature,
          maxTokens: legacy.maxTokens,
          active: true,
          createTime: new Date().toLocaleString()
        }]
        persist(list)
      }
    } catch (e) {
      console.warn('Failed to migrate legacy config', e)
    }
  }
  configList.value = list || []
})

// ---------- CRUD ----------
const handleAdd = () => {
  dialogMode.value = 'add'
  editingId.value = null
  Object.assign(dialogForm, {
    name: '',
    provider: 'deepseek',
    baseUrl: 'https://api.deepseek.com/v1',
    apiKey: '',
    modelName: 'deepseek-chat',
    temperature: 0.7,
    maxTokens: 4096
  })
  probeResult.value = null
  dialogVisible.value = true
}

const handleEdit = (row) => {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(dialogForm, {
    name: row.name,
    provider: row.provider,
    baseUrl: row.baseUrl,
    apiKey: row.apiKey,
    modelName: row.modelName,
    temperature: row.temperature,
    maxTokens: row.maxTokens
  })
  probeResult.value = null
  dialogVisible.value = true
}

const handleDialogSave = async () => {
  try {
    await dialogFormRef.value.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    let list = configList.value.slice()
    if (dialogMode.value === 'add') {
      const isFirst = list.length === 0
      list.push({
        id: 'cfg_' + Date.now(),
        name: dialogForm.name.trim(),
        provider: dialogForm.provider,
        baseUrl: dialogForm.baseUrl.trim(),
        apiKey: dialogForm.apiKey.trim(),
        modelName: dialogForm.modelName.trim(),
        temperature: dialogForm.temperature,
        maxTokens: dialogForm.maxTokens,
        active: isFirst,
        createTime: new Date().toLocaleString()
      })
    } else {
      const idx = list.findIndex(c => c.id === editingId.value)
      if (idx >= 0) {
        list[idx] = { ...list[idx], ...{
          name: dialogForm.name.trim(),
          provider: dialogForm.provider,
          baseUrl: dialogForm.baseUrl.trim(),
          apiKey: dialogForm.apiKey.trim(),
          modelName: dialogForm.modelName.trim(),
          temperature: dialogForm.temperature,
          maxTokens: dialogForm.maxTokens
        } }
      }
    }
    persist(list)
    configList.value = list
    dialogVisible.value = false
    ElMessage.success(dialogMode.value === 'add' ? '配置已新增' : '配置已更新')
  } finally {
    saving.value = false
  }
}

const handleEnable = (row) => {
  const list = configList.value.map(c => ({ ...c, active: c.id === row.id }))
  persist(list)
  configList.value = list
  ElMessage.success(`已切换生效配置：${row.name}`)
}

const handleDelete = (row) => {
  ElMessageBox.confirm(
    `确定删除配置「${row.name}」吗？${row.active ? '它当前处于使用中，删除后对话将回退到未配置状态。' : ''}`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删 除', cancelButtonText: '取 消' }
  ).then(() => {
    let list = configList.value.filter(c => c.id !== row.id)
    if (row.active) {
      // 删除使用中的配置：同步清掉旧键，对话页自动回退到未配置状态
      localStorage.removeItem(LEGACY_KEY)
    }
    localStorage.setItem(LIST_KEY, JSON.stringify(list))
    configList.value = list
    ElMessage.success('配置已删除')
  }).catch(() => {})
}

// ---------- 探针与提供商 ----------
const handleProbe = async () => {
  if (!dialogForm.apiKey || !dialogForm.apiKey.trim()) {
    ElMessage.warning('请先填写 API Key 再发起连通性测试')
    return
  }
  probing.value = true
  probeResult.value = null
  try {
    const res = await probeModelApi({
      tenantId: '1001',
      modelName: dialogForm.modelName,
      baseUrl: dialogForm.baseUrl,
      apiKey: dialogForm.apiKey,
      provider: dialogForm.provider
    })
    if (res.data) {
      probeResult.value = { ok: true, latencyMs: res.data.latencyMs }
      ElMessage.success(`连通性正常，真实往返延迟 ${res.data.latencyMs}ms`)
    }
  } catch (err) {
    probeResult.value = { ok: false, error: err.message || '端点响应异常' }
  } finally {
    probing.value = false
  }
}

const handleProviderChange = (val) => {
  if (val === 'deepseek') {
    dialogForm.baseUrl = 'https://api.deepseek.com/v1'
    dialogForm.modelName = 'deepseek-chat'
  } else if (val === 'aliyun') {
    dialogForm.baseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
    dialogForm.modelName = 'qwen-plus'
  } else if (val === 'openai') {
    dialogForm.baseUrl = 'https://api.openai.com/v1'
    dialogForm.modelName = 'gpt-4o'
  } else {
    dialogForm.baseUrl = 'http://127.0.0.1:11434/v1'
    dialogForm.modelName = 'qwen2.5:7b'
  }
}
</script>

<style scoped lang="scss">
.model-config-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
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

    .title-actions {
      display: flex;
      align-items: center;
      gap: 12px;
    }
  }
}

.name-text {
  font-weight: 600;
  color: #303133;
}

.ml-2 {
  margin-left: 6px;
}

.storage-tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.probe-result {
  width: 100%;
  margin-top: 10px;
  background: #f8fafc;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12.5px;

  p { margin: 2px 0; }
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
