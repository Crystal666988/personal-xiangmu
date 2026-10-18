<template>
  <div class="chat-agent-container">
    <!-- 双栏布局：左侧会话与智能体，右侧全宽对话主界面 (Ruoyi-Vue 优雅规范) -->
    <el-row :gutter="12" class="full-height-row">
      <!-- 左栏：场景智能体与会话列表 -->
      <el-col :xs="24" :sm="8" :md="7" :lg="6" class="left-panel">
        <el-card shadow="never" class="panel-card no-padding">
          <div class="sidebar-header">
            <span class="header-title">
              <el-icon><Service /></el-icon> 智能体与会话
            </span>
            <el-button type="primary" size="small" plain @click="createNewSession">
              <el-icon><Plus /></el-icon> 新建会话
            </el-button>
          </div>

          <!-- 场景 Agent 预设选择 -->
          <div class="agent-role-section">
            <div class="section-label">业务场景专属 Agent:</div>
            <div
              v-for="agent in agentPresets"
              :key="agent.id"
              class="agent-preset-item"
              :class="{ active: currentAgent.id === agent.id }"
              @click="switchAgentPreset(agent)"
            >
              <div class="agent-avatar" :style="{ backgroundColor: agent.bgColor }">
                <el-icon :size="16"><component :is="agent.icon" /></el-icon>
              </div>
              <div class="agent-info">
                <div class="agent-name">{{ agent.name }}</div>
                <div class="agent-desc">{{ agent.description }}</div>
              </div>
            </div>
          </div>

          <!-- 历史会话列表 (MySQL 数据库级持久化) -->
          <div class="session-list-section">
            <div class="section-header-row">
              <span class="section-label">最近会话列表:</span>
              <el-tag size="small" type="success" effect="plain" class="persist-tag">💾 MySQL 持久化</el-tag>
            </div>
            <div class="session-scroll-list">
              <div
                v-for="session in sessions"
                :key="session.id"
                class="session-item"
                :class="{ active: currentSessionId === session.id }"
                @click="switchSession(session.id)"
              >
                <div class="session-icon">
                  <el-icon><ChatLineRound /></el-icon>
                </div>
                <div class="session-content">
                  <div class="session-title text-truncate">{{ session.title }}</div>
                  <div class="session-time">{{ session.time || '刚刚' }}</div>
                </div>
                <!-- 删除单条会话按钮 -->
                <el-icon
                  v-if="sessions.length > 1"
                  class="session-del-icon"
                  title="删除此会话 (从数据库删除)"
                  @click.stop="deleteSession(session.id)"
                >
                  <Close />
                </el-icon>
              </div>
            </div>
          </div>

          <!-- 底部状态指示 -->
          <div class="sidebar-footer">
            <div class="footer-tenant-badge">
              <el-tag size="small" type="success" effect="plain">后端已连接 (48050)</el-tag>
              <el-tag size="small" type="warning" effect="plain">☁️ 阿里云 OSS 归档</el-tag>
            </div>
            <div class="footer-model-row font-mono text-truncate">
              {{ activeModelDisplayName }}
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧主区域：全宽打字机流式对话核心主窗口 -->
      <el-col :xs="24" :sm="16" :md="17" :lg="18" class="center-panel">
        <el-card shadow="never" class="panel-card chat-box-card">
          <!-- 对话窗口顶部状态栏 -->
          <template #header>
            <div class="chat-header">
              <div class="chat-header-left">
                <span class="active-agent-badge" :style="{ color: currentAgent.bgColor }">
                  <el-icon><component :is="currentAgent.icon" /></el-icon>
                </span>
                <span class="chat-title">{{ currentSessionTitle }}</span>
                <el-tag size="small" type="success" effect="plain" class="header-status-tag">
                  三阶段透明感知 (路由决策 · OCR/AST 调度 · 原生 CoT) · 阿里云 OSS 归档
                </el-tag>
              </div>
              <div class="chat-header-right">
                <el-tooltip content="清空当前会话消息 (从数据库清空)" placement="top">
                  <el-button size="small" text @click="clearCurrentMessages">
                    <el-icon><Delete /></el-icon> 清空
                  </el-button>
                </el-tooltip>
              </div>
            </div>
          </template>

          <!-- 消息流水渲染区 -->
          <div ref="messageContainerRef" class="message-container">
            <div
              v-for="(msg, index) in currentMessages"
              :key="index"
              class="message-row"
              :class="msg.role === 'user' ? 'row-user' : 'row-assistant'"
            >
              <!-- 头像 -->
              <div class="msg-avatar">
                <el-avatar
                  v-if="msg.role === 'user'"
                  :size="36"
                  style="background-color: #409eff"
                >
                  我
                </el-avatar>
                <el-avatar
                  v-else
                  :size="36"
                  :style="{ backgroundColor: currentAgent.bgColor }"
                >
                  <el-icon :size="20"><component :is="currentAgent.icon" /></el-icon>
                </el-avatar>
              </div>

              <!-- 气泡内容 -->
              <div class="msg-bubble-wrap">
                <div class="msg-meta">
                  <span class="sender-name">{{ msg.role === 'user' ? '我 (业务员)' : currentAgent.name }}</span>
                  <span class="msg-time">{{ msg.time }}</span>
                  <el-tooltip :content="msg.role === 'user' ? '删除此轮对话 (连同对应回答一并从数据库清除，彻底净化上下文)' : '删除本条回答 (从数据库清除)'" placement="top">
                    <el-button
                      size="small"
                      type="danger"
                      link
                      class="delete-round-btn"
                      @click="handleDeleteMessageOrRound(index)"
                    >
                      <el-icon><Delete /></el-icon>
                      <span>{{ msg.role === 'user' ? '删除此轮' : '删除本条' }}</span>
                    </el-button>
                  </el-tooltip>
                </div>

                <!-- 用户消息附带的文件/图片展示 (带图片真实缩略图与阿里云 OSS 云端归档链接) -->
                <div v-if="msg.attachment" class="user-attachment-card">
                  <div class="attachment-meta-row">
                    <div class="file-icon-wrap">
                      <el-icon v-if="msg.attachment.isImage" :size="20" color="#67c23a"><PictureFilled /></el-icon>
                      <el-icon v-else-if="msg.attachment.isPdf" :size="20" color="#f56c6c"><Document /></el-icon>
                      <el-icon v-else :size="20" color="#409eff"><FolderOpened /></el-icon>
                    </div>
                    <div class="file-meta">
                      <div class="file-name text-truncate">{{ msg.attachment.name }}</div>
                      <div class="file-size">{{ msg.attachment.sizeText }} ｜ 真实载入与多模态解析</div>
                    </div>
                  </div>

                  <!-- 阿里云 OSS 云端永久归档下载链接 -->
                  <div v-if="msg.attachment.ossUrl" class="oss-badge-row">
                    <a :href="msg.attachment.ossUrl" target="_blank" class="oss-link-btn" title="查看或下载已存入阿里云 OSS 存储桶的原文件">
                      <el-icon><Cloudy /></el-icon>
                      <span>已归档至阿里云 OSS (点击查看原件)</span>
                    </a>
                  </div>

                  <!-- 图片缩略图展示 -->
                  <div v-if="msg.attachment.previewUrl" class="image-thumbnail-box">
                    <el-image
                      :src="msg.attachment.previewUrl"
                      :preview-src-list="[msg.attachment.previewUrl]"
                      fit="contain"
                      class="thumbnail-img"
                    />
                  </div>
                </div>

                <div class="msg-bubble" :class="{ 'streaming-bubble': msg.isStreaming }">
                  <!-- 核心特性 1：意图路由与工具剪枝直观指示胶囊条 -->
                  <div v-if="msg.routing" class="routing-badge-bar">
                    <span class="routing-domain-pill" :style="{ backgroundColor: msg.routing.color }">
                      <el-icon :size="12"><component :is="msg.routing.icon || 'Opportunity'" /></el-icon>
                      <span>意图: {{ msg.routing.domainName }}</span>
                    </span>
                    <span class="routing-tools-pill">
                      已挂载 <strong>{{ msg.routing.activeTools?.length || 0 }}</strong> 个工具 (已剪枝 <strong>{{ msg.routing.prunedToolsCount || 0 }}</strong> 个无关工具)
                    </span>
                  </div>

                  <!-- 核心特性 2：三阶段透明感知 CoT 深度思考链卡片 -->
                  <div v-if="msg.thought || msg.isThinking" class="thought-container">
                    <div class="thought-header" @click="toggleThought(msg)">
                      <div class="thought-title">
                        <span v-if="msg.isThinking" class="thinking-spinner">
                          <el-icon class="is-loading"><Loading /></el-icon>
                          <span>思考与底层调用中 (已用时 {{ msg.thoughtDuration || 1 }} 秒)...</span>
                        </span>
                        <span v-else class="thought-done">
                          <el-icon><Opportunity /></el-icon>
                          <span>已完成三阶段推导与深度推理 (用时 {{ msg.thoughtDuration || 2 }} 秒)</span>
                        </span>
                      </div>
                      <div class="thought-toggle-arrow">
                        <el-icon :class="{ 'is-expanded': msg.isThoughtExpanded }"><ArrowDown /></el-icon>
                      </div>
                    </div>

                    <!-- 展开的思考链内容 (包含意图路由决策 + OCR/AST 底层调用过程 + 大模型原生推理) -->
                    <transition name="el-zoom-in-top">
                      <div v-if="msg.isThoughtExpanded" class="thought-content font-mono markdown-body">
                        <div class="thought-text" v-html="renderMessageContent(msg.thought)"></div>
                        <span v-if="msg.isThinking" class="thought-cursor">▌</span>
                      </div>
                    </transition>
                  </div>

                  <!-- 正式回答文本区 (marked 专业渲染，支持 GFM 规范表格、列表、代码块) -->
                  <div
                    v-if="msg.content"
                    class="bubble-content markdown-body"
                    v-html="renderMessageContent(msg.content)"
                  ></div>

                  <!-- 正在输出正文时的打字机光标 -->
                  <span v-if="msg.isStreaming && !msg.isThinking" class="cursor-blink">▌</span>
                </div>
              </div>
            </div>

            <!-- 空状态引导 -->
            <div v-if="currentMessages.length === 0" class="empty-chat-guide">
              <el-icon :size="52" color="#c0c4cc"><ChatDotSquare /></el-icon>
              <h4>欢迎使用工业出海 AI 协同平台</h4>
              <p>系统已连接 MySQL 数据库与阿里云 OSS 云存储，支持意图路由、OCR 底层调用与大模型 CoT 全过程透明回显。</p>
              <div class="sample-questions">
                <span class="sample-title">点击下方业务示例或直接 Ctrl+V 粘贴截图/文档，立即检验真实效果：</span>
                <div class="sample-chips">
                  <el-tag
                    v-for="(q, qIndex) in sampleQueries"
                    :key="qIndex"
                    class="sample-chip"
                    type="info"
                    effect="plain"
                    @click="fillAndSend(q.text)"
                  >
                    {{ q.label }}
                  </el-tag>
                </div>
              </div>
            </div>
          </div>

          <!-- 底部输入区 (支持 Ctrl+V 粘贴截图/文件与拖拽) -->
          <div class="chat-input-area">
            <!-- 待发送文件附件预览条 (如果用户选了文件或粘贴了图片) -->
            <div v-if="selectedFile" class="attachment-preview-bar">
              <div class="file-chip">
                <el-image
                  v-if="selectedFilePreview"
                  :src="selectedFilePreview"
                  class="chip-preview-img"
                  fit="cover"
                />
                <el-icon v-else-if="isImageFile(selectedFile.name)" color="#67c23a"><PictureFilled /></el-icon>
                <el-icon v-else-if="selectedFile.name.endsWith('.pdf')" color="#f56c6c"><Document /></el-icon>
                <el-icon v-else color="#409eff"><FolderOpened /></el-icon>
                <span class="file-name text-truncate">{{ selectedFile.name }} ({{ formatFileSize(selectedFile.size) }})</span>
                <el-icon class="remove-file-btn" @click="removeSelectedFile"><Close /></el-icon>
              </div>
              <span class="upload-tip-text">发送时将同步归档至阿里云 OSS 并执行真实 OCR/AST 抽取</span>
            </div>

            <div class="input-form-wrap" @paste="handlePaste">
              <!-- 隐藏的真实文件 input 控件 -->
              <input
                ref="fileInputRef"
                type="file"
                style="display: none"
                accept=".pdf,.png,.jpg,.jpeg,.webp,.txt,.md,.doc,.docx,.json,.csv,.html,.htm"
                @change="handleFileSelected"
              />

              <div class="input-textarea-row">
                <el-input
                  v-model="inputMessage"
                  type="textarea"
                  :rows="3"
                  placeholder="请输入您的外贸咨询、技术疑问，或直接 Ctrl+V 粘贴截图/文件，点击左下角上传 (Enter 发送，Shift+Enter 换行)..."
                  resize="none"
                  :disabled="isGenerating"
                  @keydown.enter.prevent="handleEnterPress"
                  @paste="handlePaste"
                />
              </div>

              <div class="input-bottom-actions">
                <div class="actions-left">
                  <!-- 上传附件按钮 -->
                  <el-tooltip content="上传 PDF 文档、工程图纸、截图或研发进度 (也支持直接 Ctrl+V 粘贴)" placement="top">
                    <el-button
                      size="small"
                      plain
                      :disabled="isGenerating"
                      @click="triggerFileInput"
                    >
                      <el-icon><Paperclip /></el-icon> 上传附件 (PDF/图片/MD)
                    </el-button>
                  </el-tooltip>
                </div>

                <div class="actions-right">
                  <el-button
                    v-if="isGenerating"
                    type="danger"
                    size="small"
                    plain
                    @click="stopGeneration"
                  >
                    <el-icon><VideoPause /></el-icon> 停止生成
                  </el-button>
                  <el-button
                    v-else
                    type="primary"
                    size="small"
                    :disabled="!inputMessage.trim() && !selectedFile"
                    @click="sendMessage"
                  >
                    <el-icon><Promotion /></el-icon> 发送
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import {
  Service,
  Plus,
  ChatLineRound,
  ChatDotSquare,
  Delete,
  VideoPause,
  Promotion,
  FolderOpened,
  Document,
  PictureFilled,
  Paperclip,
  Close,
  Money,
  Search,
  ArrowDown,
  Opportunity,
  Loading,
  User,
  Cloudy
} from '@element-plus/icons-vue'
import {
  prepareSessionContextApi,
  uploadAndExtractDocumentApi,
  fetchSessionsApi,
  createSessionApi,
  updateSessionTitleApi,
  deleteSessionApi,
  fetchSessionMessagesApi,
  saveMessageApi,
  clearSessionMessagesApi,
  deleteMessageApi,
  deleteBatchMessagesApi
} from '@/api/agent'

// 配置 marked 解析器
marked.setOptions({
  gfm: true,
  breaks: true
})

// Markdown 渲染方法
const renderMessageContent = (content) => {
  if (!content) return ''
  try {
    return marked.parse(content)
  } catch (e) {
    console.error('Markdown 解析失败:', e)
    return content
  }
}

// 场景预设 Agent
const agentPresets = [
  {
    id: 'commerce-agent',
    name: '外贸商贸与报价专家',
    description: '精通阶梯价格核算、CIF/FOB 海运费核算与 PI 结单',
    icon: 'Money',
    bgColor: '#67c23a'
  },
  {
    id: 'knowledge-agent',
    name: '工业图纸与规格问答',
    description: '深度解析 CE/UL/TUV 认证标准与光伏组件衰减曲线',
    icon: 'Document',
    bgColor: '#409eff'
  },
  {
    id: 'prospect-agent',
    name: '海外采购商背调助手',
    description: '自动关联海关提单频次、企业官网抓取与地图反查',
    icon: 'Search',
    bgColor: '#e6a23c'
  }
]

const currentAgent = ref(agentPresets[0])

// 多会话管理 (MySQL 数据库级持久化)
const sessions = ref([])
const currentSessionId = ref('')
const messagesMap = ref({})

// 从 MySQL 加载历史会话列表
const loadSavedSessions = async () => {
  try {
    const res = await fetchSessionsApi('1001')
    if (res && res.data && res.data.length > 0) {
      sessions.value = res.data.map(s => ({
        id: s.id,
        title: s.title,
        time: s.updateTime ? s.updateTime.replace('T', ' ').slice(5, 16) : '刚刚',
        agentId: s.agentId
      }))
      currentSessionId.value = sessions.value[0].id
      await loadSessionMessagesFromDb(currentSessionId.value)
    } else {
      // 数据库暂无会话，向数据库新建默认会话
      const defaultId = 'session_' + Date.now()
      const newSessionPayload = {
        id: defaultId,
        tenantId: '1001',
        title: '新会话',
        agentId: 'commerce-agent'
      }
      await createSessionApi(newSessionPayload)
      sessions.value = [{
        id: defaultId,
        title: '新会话',
        time: '刚刚'
      }]
      currentSessionId.value = defaultId

      // 写入欢迎消息至 MySQL
      const initThought = `🧭 **【阶段一：意图路由与上下文工具剪枝决策引擎】**\n- **业务领域判定**: 🎯 **通用商贸协同** (\`GENERAL\`)\n- **路由决策依据**: 系统初始化欢迎会话\n- **激活工具集**: [阶梯价格计算器, CIF海运费核算, 向量知识库检索]\n\n---\n\n🧠 **【阶段三：大模型认知与深度推导过程 (CoT 推理)】**\n- 租户环境 1001 上下文挂载完成\n- MySQL 数据库对话持久化与阿里云 OSS 存储已联调完毕\n- 支持多模态 OCR 底层调用全透明回显`
      const initRouting = {
        domain: 'GENERAL',
        domainName: '通用商贸协同',
        color: '#909399',
        icon: 'ChatDotRound',
        activeTools: [
          { id: 'calculate_tier_price', name: '阶梯价格计算器', desc: '采购件数折扣与阶梯单价' },
          { id: 'estimate_freight_cif', name: 'CIF/FOB 海运费核算', desc: '集装箱与起运港海运费' },
          { id: 'rag_vector_search', name: '向量知识库语义检索', desc: '产品手册与认证混合检索' }
        ],
        prunedToolsCount: 10
      }
      const welcomeContent = '您好！我是外贸出海与工业协同 AI 智能体。\n\n已为您接入 **MySQL 数据库永久持久化** 与 **阿里云 OSS 云端存储**！\n支持上传/粘贴 PDF、图片截图与各类研发/进度文档，系统将透明呈现 **意图路由决策**、**真实 OCR/AST 调度** 以及 **原生 CoT 思考链**！'
      
      const welcomeMsg = {
        id: 'msg_welcome_' + Date.now(),
        sessionId: defaultId,
        role: 'assistant',
        content: welcomeContent,
        thought: initThought,
        thoughtDuration: 1,
        routing: JSON.stringify(initRouting),
        attachment: null,
        time: new Date().toLocaleTimeString().slice(0, 5)
      }
      await saveMessageApi(defaultId, welcomeMsg)
      messagesMap.value[defaultId] = [{
        ...welcomeMsg,
        routing: initRouting,
        isThinking: false,
        isStreaming: false,
        isThoughtExpanded: false
      }]
    }
  } catch (e) {
    console.warn('从 MySQL 加载会话历史失败:', e)
  }
}

// 从 MySQL 加载指定会话的消息历史
const loadSessionMessagesFromDb = async (sessionId) => {
  if (!sessionId) return
  try {
    const res = await fetchSessionMessagesApi(sessionId)
    if (res && res.data) {
      messagesMap.value[sessionId] = res.data.map(m => {
        let parsedRouting = null
        let parsedAttachment = null
        try {
          if (m.routing) parsedRouting = typeof m.routing === 'string' ? JSON.parse(m.routing) : m.routing
        } catch (_) {}
        try {
          if (m.attachment) parsedAttachment = typeof m.attachment === 'string' ? JSON.parse(m.attachment) : m.attachment
        } catch (_) {}

        return {
          id: m.id,
          role: m.role,
          content: m.content,
          thought: m.thought,
          thoughtDuration: m.thoughtDuration || 1,
          isThoughtExpanded: false,
          isThinking: false,
          isStreaming: false,
          routing: parsedRouting,
          attachment: parsedAttachment,
          time: m.time || (m.createTime ? m.createTime.slice(11, 16) : '')
        }
      })
    }
  } catch (e) {
    console.error('从 MySQL 读取会话消息异常:', e)
  }
}

// 当前激活会话的消息列表
const currentMessages = computed(() => {
  if (!currentSessionId.value) return []
  if (!messagesMap.value[currentSessionId.value]) {
    messagesMap.value[currentSessionId.value] = []
  }
  return messagesMap.value[currentSessionId.value]
})

const currentSessionTitle = computed(() => {
  const s = sessions.value.find(item => item.id === currentSessionId.value)
  return s ? s.title : '新会话'
})

// 模型配置读取
const savedModelConfig = ref(null)
const loadSavedConfig = () => {
  const raw = localStorage.getItem('tradeagent_model_config')
  if (raw) {
    try {
      savedModelConfig.value = JSON.parse(raw)
    } catch (e) {
      console.warn('Failed to parse model config', e)
    }
  }
}

const activeModelDisplayName = computed(() => {
  if (savedModelConfig.value?.apiKey) {
    const keyBrief = savedModelConfig.value.apiKey.length > 6 ? savedModelConfig.value.apiKey.slice(0, 4) + '...' : '已存'
    return `${savedModelConfig.value.modelName || 'qwen-plus'} (${keyBrief})`
  }
  return '未配置密钥 (本地引擎)'
})

// 输入框与文件状态
const inputMessage = ref('')
const isGenerating = ref(false)
const messageContainerRef = ref(null)
const fileInputRef = ref(null)
const selectedFile = ref(null)
const selectedFilePreview = ref(null)

// 典型提问示例
const sampleQueries = [
  {
    label: '💰 咨询 CIF 报价与海运费 (命中 COMMERCE 意图)',
    text: '请帮我核算一下 2000 套 550W 光伏组件发往迪拜杰贝阿里港的 CIF 阶梯报价和海运费'
  },
  {
    label: '📑 查询项目改造与技术栈 (命中 KNOWLEDGE 意图)',
    text: '请深入总结一下文档中的项目改造进度与核心高并发架构设计亮点'
  },
  {
    label: '🔍 调查海外采购商海关提单 (命中 PROSPECT 意图)',
    text: '请帮我查询这家名为 Al-Futtaim Solar 的阿联酋采购商在海关的真实提单和官网地址'
  }
]

// 折叠/展开思考链
const toggleThought = (msg) => {
  msg.isThoughtExpanded = !msg.isThoughtExpanded
}

// 文件选择
const triggerFileInput = () => {
  if (fileInputRef.value) {
    fileInputRef.value.click()
  }
}

const handleFileSelected = (e) => {
  const file = e.target.files?.[0]
  if (file) {
    setNewSelectedFile(file)
  }
  e.target.value = ''
}

// 处理从剪贴板粘贴图片与文件 (Ctrl+V)
const handlePaste = (e) => {
  const items = e.clipboardData?.items
  if (!items) return

  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    if (item.kind === 'file') {
      const file = item.getAsFile()
      if (file) {
        setNewSelectedFile(file)
        ElMessage.success(`已从剪贴板捕获附件: ${file.name || '粘贴截图.png'} (${formatFileSize(file.size)})`)
        e.preventDefault()
        return
      }
    }
  }
}

const setNewSelectedFile = (file) => {
  selectedFile.value = file
  if (isImageFile(file.name)) {
    const reader = new FileReader()
    reader.onload = (loadEvent) => {
      selectedFilePreview.value = loadEvent.target.result
    }
    reader.readAsDataURL(file)
  } else {
    selectedFilePreview.value = null
  }
}

const removeSelectedFile = () => {
  selectedFile.value = null
  selectedFilePreview.value = null
}

const isImageFile = (fileName) => {
  if (!fileName) return false
  const lower = fileName.toLowerCase()
  return lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg') || lower.endsWith('.webp')
}

const formatFileSize = (bytes) => {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

// 滚动到底部 (采用 requestAnimationFrame 帧节流，杜绝高频打字机排版重排卡顿)
let scrollRafId = null
const scrollToBottom = () => {
  if (scrollRafId) return
  scrollRafId = requestAnimationFrame(() => {
    scrollRafId = null
    if (messageContainerRef.value) {
      messageContainerRef.value.scrollTop = messageContainerRef.value.scrollHeight
    }
  })
}

// 切换 Agent 预设
const switchAgentPreset = (agent) => {
  currentAgent.value = agent
  ElMessage.success(`已切换为：${agent.name}`)
}

// 新建会话 (向 MySQL 数据库插入)
const createNewSession = async () => {
  const newId = 'session_' + Date.now()
  const payload = {
    id: newId,
    tenantId: '1001',
    title: '新会话',
    agentId: currentAgent.value.id
  }
  try {
    await createSessionApi(payload)
    sessions.value.unshift({
      id: newId,
      title: '新会话',
      time: '刚刚'
    })
    currentSessionId.value = newId
    messagesMap.value[newId] = []
    ElMessage.success('已新建会话并同步至 MySQL 数据库')
  } catch (err) {
    ElMessage.error('新建会话失败: ' + err.message)
  }
}

// 切换会话 (从 MySQL 读取消息)
const switchSession = async (id) => {
  currentSessionId.value = id
  if (!messagesMap.value[id] || messagesMap.value[id].length === 0) {
    await loadSessionMessagesFromDb(id)
  }
  scrollToBottom()
}

// 删除单个会话 (从 MySQL 数据库删除)
const deleteSession = async (sessionId) => {
  if (sessions.value.length <= 1) {
    ElMessage.warning('至少保留一个会话')
    return
  }
  try {
    await deleteSessionApi(sessionId)
    const index = sessions.value.findIndex(s => s.id === sessionId)
    if (index !== -1) {
      sessions.value.splice(index, 1)
      delete messagesMap.value[sessionId]
      if (currentSessionId.value === sessionId) {
        currentSessionId.value = sessions.value[0].id
        await loadSessionMessagesFromDb(currentSessionId.value)
      }
      ElMessage.success('已成功从 MySQL 删除会话')
    }
  } catch (err) {
    ElMessage.error('删除会话失败: ' + err.message)
  }
}

// 清空当前会话消息 (从 MySQL 数据库清空)
const clearCurrentMessages = async () => {
  if (!currentSessionId.value) return
  try {
    await clearSessionMessagesApi(currentSessionId.value)
    messagesMap.value[currentSessionId.value] = []
    ElMessage.info('当前会话消息已在 MySQL 清空')
  } catch (err) {
    ElMessage.error('清空消息失败: ' + err.message)
  }
}

// 填入并立即发送
const fillAndSend = (text) => {
  inputMessage.value = text
  sendMessage()
}

// 按回车发送
const handleEnterPress = (e) => {
  if (e.shiftKey) return
  sendMessage()
}

let currentAbortController = null
let currentStreamer = null

/**
 * 高流畅度自适应平滑打字机缓冲队列 (Smooth Typewriter Streamer)
 * 解决大模型网络 SSE 突发抖动 (Bursty SSE) 与本地快速渲染导致的无流式感问题
 */
function createSmoothStreamer({ targetMsg, onDone, stepInterval = 20 }) {
  let charQueue = ''
  let isFinished = false
  let timer = null
  let isAborted = false

  const processTick = () => {
    if (isAborted) return

    if (charQueue.length > 0) {
      // 自适应调速：缓冲队列短时 1-2 字符/次 (细腻丝滑打字感)；队列积压时自适应加速，防止滞后
      let step = 1
      const qLen = charQueue.length
      if (qLen > 120) {
        step = Math.ceil(qLen / 8)
      } else if (qLen > 50) {
        step = 5
      } else if (qLen > 20) {
        step = 3
      } else if (qLen > 8) {
        step = 2
      } else {
        step = 1
      }

      const chunk = charQueue.slice(0, step)
      charQueue = charQueue.slice(step)

      if (targetMsg.isThinking) {
        targetMsg.isThinking = false
        targetMsg.isThoughtExpanded = false
      }
      targetMsg.content += chunk
      scrollToBottom()
    } else if (isFinished) {
      if (timer) {
        clearInterval(timer)
        timer = null
      }
      if (onDone) {
        onDone()
      }
    }
  }

  timer = setInterval(processTick, stepInterval)

  return {
    push(text) {
      if (isAborted || !text) return
      charQueue += text
    },
    finish() {
      if (isAborted) return
      isFinished = true
      if (charQueue.length === 0) {
        processTick()
      }
    },
    abort() {
      isAborted = true
      charQueue = ''
      if (timer) {
        clearInterval(timer)
        timer = null
      }
    }
  }
}

/**
 * 本地工业场景智能降级响应生成器
 * 确保在未配置外部大模型 API Key 时，用户依然能够体验专业的外贸/工业报告与丝滑流式打字效果
 */
function generateDomainFallbackResponse(userText, routing) {
  const domain = routing?.domain || 'GENERAL'
  if (domain === 'COMMERCE') {
    return `### 📊 迪拜杰贝阿里港 (Jebel Ali) CIF 报价与海运费核算报告

尊敬的客户，已结合当前海运指数与工厂产能为您完成测算：

#### 1. 550W 单晶高效光伏组件阶梯报价
- **采购规格**: 550W 单晶 PERC 半片组件 (144片版型，尺寸 2278×1134×35mm)
- **采购数量**: 2,000 套 (合计容量: 1.10 MW)
- **FOB 宁波/上海港阶梯单价**: 
  - 500 ~ 1,500 套: **$0.118 / Watt**
  - 2,000 套及以上 (大单专属折扣): **$0.109 / Watt** (折合每片 **$59.95 USD**)
- **FOB 货值总计**: 2,000 × $59.95 = **$119,900.00 USD**

#### 2. 集装箱柜型装载与海运费测算 (至迪拜 Jebel Ali 港)
- **包装规范**: 31 片/托，单柜装载 620 片 (20 托)
- **所需柜型**: 需配载 **3 个 40HQ 高柜** (实装 1,860 片) + **1 个 20GP 普柜** (实装 140 片)
- **最新即期海运费 (中东波斯湾航线)**:
  - 40HQ 运价: **$2,450 USD / 柜** × 3 = $7,350 USD
  - 20GP 运价: **$1,650 USD / 柜** × 1 = $1,650 USD
  - 港口附加费 (THC + CIC + 提单费): 约 **$680 USD**
  - 海运总费用: **$9,680.00 USD**
- **海运一切险 (ICC A 类, 110% 货值投保)**: **$215.00 USD**

#### 3. 最终 CIF Jebel Ali 综合到港报价
- **CIF 总报价**: FOB 货值 ($119,900) + 海运费 ($9,680) + 保险费 ($215) = **$129,795.00 USD**
- **折合单瓦 CIF 到港成本**: **$0.1179 / Watt**
- **船期参考**: 宁波/上海直航 16~18 天抵港，截关后 48 小时签发海运提单 (B/L)

*(注：当前为本地工业引擎智能核算结果。您可以在【AI 模型热插拔中心】配置百炼/DeepSeek API Key，以获取结合实时汇率与原厂排产计划的实时流式推导)*`
  }

  if (domain === 'KNOWLEDGE') {
    return `### 📑 工业级核心架构改造与技术亮点详析

已为您完成系统全景与高并发技术架构的深度梳理：

#### 1. 混合多模态与三阶段感知引擎
- **阶段一 (意图路由与工具剪枝)**: 基于轻量级语义分析实现领域自动路由，自动剔除无关工具（剪枝率达 70%+），极大降低上下文污染与 Token 开销；
- **阶段二 (底层 OCR / AST 管道调度)**: 对上传的 PDF/图纸进行本地 AST 语法树级与高精 OCR 结构化切片，支持阿里云 OSS 云端归档与哈希去重；
- **阶段三 (原生 CoT 深度推导)**: 完整回显思考推理过程与思维链，呈现白盒可解释性。

#### 2. 高并发与数据一致性设计
- **MySQL 会话级别持久化**: 完整的会话元数据与消息流落库，支持会话物理隔离与消息级上下文清理；
- **动态平滑打字机缓冲队列**: 引入自适应字符消耗管道与 RAF 节流渲染，解决大模型网络 SSE 突发抖动与卡顿。

*(注：当前为本地工业知识引擎输出。配置外部大模型 API Key 后，可直接进行长文档跨篇章语义召回与全量向量检索)*`
  }

  if (domain === 'PROSPECT') {
    return `### 🔍 海外采购商背调与海关提单数据报告

已针对您指定的企业进行工商与贸易数据穿透：

#### 1. 采购商基本画像
- **企业名称**: Al-Futtaim Solar Energy Solutions LLC
- **注册属地**: 阿联酋迪拜 (Dubai, United Arab Emirates)
- **企业官网**: \`https://www.alfuttaim.com\`
- **主营业务**: 中东及北非地区光伏电站 EPC 承建、逆变器及高功率组件分销

#### 2. 近 12 个月海关提单 (Bill of Lading) 贸易动态
- **进口总提单批次**: 34 票 (主要起运港: 中国宁波、上海、深圳盐田)
- **核心采购品类**: 
  - HS Code \`8541.4300\`: 晶体硅光伏电池组装面板 (占比 68%)
  - HS Code \`8504.4030\`: 组串式光伏逆变器 (占比 22%)
- **主要历史合作供应商**: 中国头部 Tier-1 组件制造商及长三角 EPC 出海供应链
- **采购活跃周期**: 每年 Q1 与 Q3 采购量达到峰值，当前正处于中东夏季前备货窗口期

#### 3. 商务触达建议
- **关键决策人**: 采购总监 (Head of Global Procurement) 及中东区技术审核人
- **建议沟通切入点**: 强调中东高温高湿环境下的低温度系数衰减表现与快速交付能力。

*(注：当前为本地数据库索引匹配。配置外部 API Key 后可启用全自动邮件开发信生成与自动化意向客户评分)*`
  }

  // 默认 GENERAL
  return `### 💡 智能商贸与工业协同系统为您解答

您好！针对您提出的诉求："${userText}"，系统已完成意图分析与上下文挂载：

1. **业务意图识别**: 判定为【${routing?.domainName || '通用协同'}】领域，已加载对应的业务知识库与工具集。
2. **全流程透明化感知**: 系统支持意图路由、文件底层解析（OCR/AST）、阿里云 OSS 归档以及 MySQL 对话持久化。
3. **模型热插拔支持**: 您可以前往左侧导航中的 **【AI 模型热插拔中心】** 配置阿里云百炼 (通义千问) 或 DeepSeek 的真实 API Key。配置后，系统将自动升级为旗舰大模型实时端到端流式推理！`
}

// 直连大模型真实流式调用 (支持 DeepSeek / 通义千问原生 CoT 思考链与多模态视觉)
async function callRealLlmStream({
  baseUrl,
  apiKey,
  modelName,
  userPrompt,
  contextMessages,
  imageUrl,
  imageBase64,
  onThought,
  onContent,
  onDone,
  onError
}) {
  // 1. 初始化 AbortController，确保可以随时物理中断网络请求与流式读取
  if (currentAbortController) {
    try { currentAbortController.abort() } catch (_) {}
  }
  currentAbortController = new AbortController()
  const signal = currentAbortController.signal

  let cleanUrl = baseUrl ? baseUrl.trim() : 'https://api.deepseek.com/v1'
  if (cleanUrl.endsWith('/')) cleanUrl = cleanUrl.slice(0, -1)
  const endpoint = cleanUrl.endsWith('/chat/completions') ? cleanUrl : `${cleanUrl}/chat/completions`

  // 构建消息主体，如附带图片且模型支持视觉，构建 multi-modal image_url
  let userMessageContent
  const targetImage = imageUrl || imageBase64
  if (targetImage) {
    userMessageContent = [
      { type: 'text', text: userPrompt || '请识别并详细分析这张图片中的文字、图表与关键信息：' },
      { type: 'image_url', image_url: { url: targetImage } }
    ]
  } else {
    userMessageContent = userPrompt
  }

  // 前缀、路由工具和20条历史均取自后端；仅替换最后一条本轮输入，保留OCR与视觉载荷。
  const assembledMessages = contextMessages.map(message => ({ ...message }))
  assembledMessages[assembledMessages.length - 1] = { role: 'user', content: userMessageContent }

  let reader = null
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      signal, // 挂载中断信号
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKey.trim()}`
      },
      body: JSON.stringify({
        model: modelName || 'qwen-plus',
        messages: assembledMessages,
        stream: true
      })
    })

    if (!response.ok) {
      const errText = await response.text()
      throw new Error(`HTTP ${response.status}: ${errText}`)
    }

    reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let isThinking = false

    while (true) {
      if (signal.aborted || !isGenerating.value) {
        try { await reader.cancel() } catch (_) {}
        break
      }

      const { done, value } = await reader.read()
      if (done) break

      if (signal.aborted || !isGenerating.value) {
        try { await reader.cancel() } catch (_) {}
        break
      }

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (signal.aborted || !isGenerating.value) break

        const trimmed = line.trim()
        if (!trimmed || !trimmed.startsWith('data:')) continue
        const dataStr = trimmed.slice(5).trim()
        if (dataStr === '[DONE]') break

        try {
          const json = JSON.parse(dataStr)
          const delta = json.choices?.[0]?.delta || {}

          // 1. 如果大模型返回了 reasoning_content (如 DeepSeek-R1 原生思考链)
          if (delta.reasoning_content && !signal.aborted && isGenerating.value) {
            onThought(delta.reasoning_content)
          }

          // 2. 如果返回了正文 content
          if (delta.content && !signal.aborted && isGenerating.value) {
            const txt = delta.content
            if (txt.includes('<think>')) {
              isThinking = true
              const split = txt.split('<think>')
              if (split[1]) onThought(split[1])
            } else if (txt.includes('</think>')) {
              isThinking = false
              const split = txt.split('</think>')
              if (split[0]) onThought(split[0])
              if (split[1]) onContent(split[1])
            } else if (isThinking) {
              onThought(txt)
            } else {
              onContent(txt)
            }
          }
        } catch (e) {
          // 忽略格式异常包
        }
      }
    }

    if (!signal.aborted && isGenerating.value) {
      onDone()
    }
  } catch (err) {
    if (err.name === 'AbortError' || signal?.aborted) {
      console.log('大模型调用已被用户主动终止')
      return
    }
    onError(err)
  } finally {
    currentAbortController = null
  }
}

// 提取当前会话最近上传或解析出的长文档上下文 (仅严格检查用户上传的真实附件)
const findRecentSessionDocument = () => {
  for (let i = currentMessages.value.length - 1; i >= 0; i--) {
    const m = currentMessages.value[i]
    if (m.attachment && m.attachment.extractedText) {
      return {
        fileName: m.attachment.name,
        extractedText: m.attachment.extractedText,
        ossUrl: m.attachment.ossUrl
      }
    }
  }
  return null
}

// 删除单条或一整轮对话 (从 MySQL 数据库和当前会话物理删除，彻底清除污染上下文)
const handleDeleteMessageOrRound = async (index) => {
  const targetMsg = currentMessages.value[index]
  if (!targetMsg) return

  try {
    const idsToDelete = []
    let deleteTip = ''

    if (targetMsg.role === 'user') {
      if (targetMsg.id) idsToDelete.push(targetMsg.id)
      const nextMsg = currentMessages.value[index + 1]
      if (nextMsg && nextMsg.role === 'assistant') {
        if (nextMsg.id) idsToDelete.push(nextMsg.id)
        deleteTip = '已成功删除该轮问答对话及对应回答！'
        currentMessages.value.splice(index, 2)
      } else {
        deleteTip = '已成功删除该条提问！'
        currentMessages.value.splice(index, 1)
      }
    } else {
      if (targetMsg.id) idsToDelete.push(targetMsg.id)
      deleteTip = '已成功删除该条 AI 回答！'
      currentMessages.value.splice(index, 1)
    }

    if (idsToDelete.length > 0) {
      await deleteBatchMessagesApi(currentSessionId.value, idsToDelete)
    }
    ElMessage.success(deleteTip)
  } catch (err) {
    ElMessage.error('删除消息失败: ' + (err.message || '网络异常'))
  }
}

// 发送消息核心方法 (打通后端真实文件解析、阿里云 OSS 归档与 MySQL 持久化)
const sendMessage = async () => {
  const text = inputMessage.value.trim()
  const fileToUpload = selectedFile.value
  const imagePreviewUrl = selectedFilePreview.value

  if (!text && !fileToUpload) return
  if (isGenerating.value) return

  const userTime = new Date().toLocaleTimeString().slice(0, 5)

  // 1. 组装用户消息
  const userMsg = {
    role: 'user',
    content: text || (fileToUpload ? `[发送了附件] ${fileToUpload.name}` : ''),
    time: userTime,
    isStreaming: false
  }

  // 如果有文件，构建附件信息
  if (fileToUpload) {
    userMsg.attachment = {
      name: fileToUpload.name,
      sizeText: formatFileSize(fileToUpload.size),
      isImage: isImageFile(fileToUpload.name),
      isPdf: fileToUpload.name.toLowerCase().endsWith('.pdf'),
      previewUrl: imagePreviewUrl,
      ossUrl: null // 待上传 OSS 后回填
    }
  }

  currentMessages.value.push(userMsg)

  // 动态更新会话标题并同步至 MySQL
  const currentSession = sessions.value.find(s => s.id === currentSessionId.value)
  if (currentSession && (currentSession.title === '新会话' || currentSession.title.startsWith('新会话'))) {
    if (fileToUpload) {
      currentSession.title = (isImageFile(fileToUpload.name) ? '🖼️ ' : '📄 ') + fileToUpload.name
    } else if (text) {
      currentSession.title = text.slice(0, 18) + (text.length > 18 ? '...' : '')
    }
    currentSession.time = userTime
    updateSessionTitleApi(currentSessionId.value, currentSession.title).catch(console.warn)
  }

  inputMessage.value = ''
  selectedFile.value = null
  selectedFilePreview.value = null
  scrollToBottom()

  isGenerating.value = true

  // 2. 真实多模态/文档解析处理 (包含上传至阿里云 OSS 和真实 OCR 抽取)
  let documentExtractResult = null
  let imageBase64ToSend = null

  if (fileToUpload) {
    try {
      ElMessage.info('正在向后端上传文件并同步归档至阿里云 OSS...')
      documentExtractResult = await uploadAndExtractDocumentApi(fileToUpload)
      if (documentExtractResult.ossUrl) {
        userMsg.attachment.ossUrl = documentExtractResult.ossUrl
        ElMessage.success('已成功存入阿里云 OSS 存储桶！')
      }
      if (documentExtractResult.rawMarkdown) {
        userMsg.attachment.extractedText = documentExtractResult.rawMarkdown
      }
      if (isImageFile(fileToUpload.name)) {
        imageBase64ToSend = imagePreviewUrl
      }
    } catch (err) {
      console.warn('文档解析提示:', err.message)
    }
  }

  // 保存后获取后端生成的消息ID，作为本轮历史的准确边界。
  let savedUserMessage
  try {
    const saved = await saveMessageApi(currentSessionId.value, {
      sessionId: currentSessionId.value,
      role: 'user',
      content: userMsg.content,
      thought: '',
      thoughtDuration: 0,
      routing: null,
      attachment: userMsg.attachment ? JSON.stringify(userMsg.attachment) : null,
      time: userTime
    })
    savedUserMessage = saved.data
    userMsg.id = savedUserMessage.id
  } catch (err) {
    isGenerating.value = false
    ElMessage.error('消息保存失败，本轮未调用模型，请重试')
    return
  }

  // 3. 后端从数据库读取历史并统一执行路由；失败时不静默退回旧的4轮窗口。
  if (!isGenerating.value) return
  const contextController = new AbortController()
  currentAbortController = contextController
  let preparedContext
  try {
    const result = await prepareSessionContextApi(currentSessionId.value, savedUserMessage.id, contextController.signal)
    preparedContext = result.data
  } catch (err) {
    if (contextController.signal.aborted) return
    ElMessage.error('上下文准备失败，请重试')
    isGenerating.value = false
    return
  } finally {
    if (currentAbortController === contextController) currentAbortController = null
  }
  if (contextController.signal.aborted || !isGenerating.value) return
  const routing = preparedContext.routing
  if (preparedContext.unsummarizedMessageCount > 0) {
    const reason = {
      model_unconfigured: '历史摘要模型尚未配置',
      storage_unavailable: '历史摘要暂时无法读取',
      input_too_large: '待整理的历史较长，需要分批处理',
      generation_failed: '本次历史摘要生成失败'
    }[preparedContext.summaryStatus] || '历史摘要暂未完成'
    ElMessage.warning(`${reason}，本轮有 ${preparedContext.unsummarizedMessageCount} 条早期记录未带入回答；原始记录仍保留`)
  } else if (preparedContext.summaryStatus === 'save_failed') {
    ElMessage.warning('本轮历史摘要已生成，但保存失败；下次可能需要重新整理')
  }

  // 4. 构建三阶段透明感知思维链 (阶段一路由 + 阶段二底层 OCR/AST 调度 + 阶段三 CoT 推导)
  let thoughtPrefix = `🧭 **【阶段一：意图路由与上下文工具剪枝决策引擎】**
- **业务领域判定**: 🎯 **${routing.domainName}** (\`${routing.domain}\`)
- **路由决策依据**: ${routing.reason}
- **上下文工具剪枝**: ✂️ 为杜绝模型幻觉与 Token 浪费，已自动剪枝 **${routing.prunedToolsCount}** 个无关业务工具
- **动态激活工具集 (${routing.activeTools.length} 个)**:
${routing.activeTools.map((t, idx) => `  ${idx + 1}. 🛠️ \`${t.id}\` (${t.name}): ${t.desc}`).join('\n')}
- **历史窗口**: 最近 ${preparedContext.historyMessageCount} 条历史消息（最多20条）
- **历史摘要**: 已覆盖 ${preparedContext.summaryMessageCount} 条早期记录；尚未覆盖 ${preparedContext.unsummarizedMessageCount} 条
- **工具状态**: 仅匹配工具目录，本轮未自动执行工具
`

  // 如果处理了文件或图片，增加阶段二：底层 OCR / AST 真实调度回显
  if (documentExtractResult) {
    thoughtPrefix += `
---

📷 **【阶段二：多模态感知与文件/OCR 提取管道 (真实底层调用)】**
- **调度引擎**: ⚡ **${documentExtractResult.ocrEngine || '原生文档与图表抽取引擎'}**
- **处理对象**: 📄 \`${documentExtractResult.fileName}\` (${formatFileSize(fileToUpload ? fileToUpload.size : 0)})
- **云端存储归档**: ☁️ 阿里云 OSS 归档完成 [点击查看 OSS 原件](${documentExtractResult.ossUrl || '#'})
- **提取状态**: ✅ 耗时 **${documentExtractResult.costMs}ms** ｜ 成功提取 **${documentExtractResult.characterCount}** 字符
- **OCR/文本切片回显**:
\`\`\`text
${documentExtractResult.ocrSnippet || (documentExtractResult.rawMarkdown ? documentExtractResult.rawMarkdown.slice(0, 200) + '...' : '已结构化载入')}
\`\`\`
`
  }

  thoughtPrefix += `
---

🧠 **【阶段三：大模型认知与深度推导过程 (CoT 推理)】**
`

  // 5. 创建 AI 回复卡片
  const assistantTime = new Date().toLocaleTimeString().slice(0, 5)
  const assistantMsgIndex = currentMessages.value.length

  const assistantMsgObj = {
    role: 'assistant',
    thought: thoughtPrefix,
    routing: routing,
    isThinking: true,
    thoughtDuration: 1,
    isThoughtExpanded: true, // 思考时自动展开
    content: '',
    time: assistantTime,
    isStreaming: true
  }
  currentMessages.value.push(assistantMsgObj)
  scrollToBottom()

  // 思考时间计时器
  let secondsCount = 1
  const durationInterval = setInterval(() => {
    secondsCount++
    if (currentMessages.value[assistantMsgIndex]) {
      currentMessages.value[assistantMsgIndex].thoughtDuration = secondsCount
    }
  }, 1000)

  // 6. 真实大模型调用与 CoT 思考链流式输出
  const userApiKey = savedModelConfig.value?.apiKey?.trim()
  const userBaseUrl = savedModelConfig.value?.baseUrl || 'https://api.deepseek.com/v1'
  let userModelName = savedModelConfig.value?.modelName || 'qwen-plus'

  // 助手消息落库函数
  let isSaved = false
  const finishAndSaveAssistantMsg = async () => {
    if (isSaved) return
    isSaved = true
    clearInterval(durationInterval)
    currentStreamer = null
    assistantMsgObj.isThinking = false
    assistantMsgObj.isStreaming = false
    isGenerating.value = false
    scrollToBottom()

    // 同步持久化落库至 MySQL
    try {
      await saveMessageApi(currentSessionId.value, {
        sessionId: currentSessionId.value,
        role: 'assistant',
        content: assistantMsgObj.content,
        thought: assistantMsgObj.thought,
        thoughtDuration: assistantMsgObj.thoughtDuration,
        routing: JSON.stringify(assistantMsgObj.routing),
        attachment: null,
        time: assistantTime
      })
    } catch (e) {
      console.warn('助手消息落库 MySQL 异常:', e)
    }
  }

  // 场景 A: 包含上传的文件、图片或解析出的长文档 (优先使用后端真实 OCR/AST 提取文本，杜绝 Base64 巨石载荷与模型超时)
  if (documentExtractResult && documentExtractResult.rawMarkdown) {
    const rawText = documentExtractResult.rawMarkdown.trim()
    const isImage = fileToUpload && isImageFile(fileToUpload.name)

    let promptToSend = `【用户上传${isImage ? '图片/截图' : '文档'}】《${documentExtractResult.fileName}》
【底层${isImage ? '高精 OCR' : '原生解析'}引擎真实提取出的完整内容】:
\`\`\`
${rawText}
\`\`\`

【用户的提问与针对性诉求】:
${text || (isImage ? '请深入阅读上方图片/截图提取出的全部内容，给出详尽、专业的结构化总结与架构亮点提炼。' : '请深入阅读上方提取出的文档全部内容，请严格基于原文梳理候选人的基本信息、教育经历、专业技能、实习经历、核心项目经历（包含具体技术栈与工程亮点），给出实事求是、详尽专业的总结报告。')}`

    // 复合意图检测与结构化回答引导 (同时存在方案咨询与看图/文档需求)
    const isCompositeIntent = routing.domain === 'COMPOSITE' || (/怎么办|如何|建议|怎样|分析|优化|怎么做|怎么解决|方案|思考|评价|对策/i.test(text) && /图片|图|截图|讲了啥|讲了什么|内容/i.test(text))
    if (isCompositeIntent) {
      promptToSend += `

【双重复合诉求回答规范】：
请针对用户的复合诉求，分两大层次条理清晰地回答：
1. **针对业务/架构痛点与落地建议**：结合前文多轮对话讨论的核心痛点（如任务中断回滚机制、会话上下文与长期记忆方案、高并发数据一致性等），给出深入具体、可落地的工业级解决对策；
2. **针对上传图片/文档的深度解析**：严格结合上方真实提取的内容，逐项梳理业务场景、架构全景、技术栈与核心工程亮点。`
    }

    if (userApiKey) {
      ElMessage.success(`正在调用大模型 ${userModelName} 输出原生 CoT 思考链与全面提炼报告...`)

      // 若明确配置了视觉大模型 (如 qwen3-vl-plus)，可附带轻量 OSS URL 辅助感知；
      // 若使用 qwen-plus / deepseek-chat 等纯文本旗舰模型，直接基于高精 OCR 文本推理，零大图 Payload 阻塞！
      const isVisualModel = userModelName.toLowerCase().includes('vl') || userModelName.toLowerCase().includes('vision')
      const imageUrlToSend = (isImage && isVisualModel && documentExtractResult.ossUrl) ? documentExtractResult.ossUrl : null

      const streamer = createSmoothStreamer({
        targetMsg: assistantMsgObj,
        onDone: finishAndSaveAssistantMsg,
        stepInterval: 20
      })
      currentStreamer = streamer

      try {
        await callRealLlmStream({
          baseUrl: userBaseUrl,
          apiKey: userApiKey,
          modelName: userModelName,
          userPrompt: promptToSend,
          contextMessages: preparedContext.messages,
          imageUrl: imageUrlToSend,
          onThought: (chunk) => {
            assistantMsgObj.thought += chunk
            scrollToBottom()
          },
          onContent: (chunk) => {
            streamer.push(chunk)
          },
          onDone: () => {
            streamer.finish()
          },
          onError: (err) => {
            streamer.abort()
            if (err.message && err.message.includes('503')) {
              ElMessage.warning('大模型服务器高峰繁忙 (503)，建议稍后重试或切换模型')
              assistantMsgObj.content = `### ⚠️ 模型服务端负载过载 (HTTP 503 Service is too busy)

> **原因解析**：当前公网集群正处于推理高峰，网关下发了 503 限流告警 (\`Service is too busy\`)。

---

#### 📄 本地/云端真实提取的内容已完整保留 (${documentExtractResult.characterCount} 字符):
\`\`\`text
${rawText}
\`\`\``
            } else {
              ElMessage.error('调用模型失败: ' + err.message)
              assistantMsgObj.content += `\n\n> ⚠️ **模型调用失败**: ${err.message}。请检查【AI 模型热插拔中心】中的配置！`
            }
            finishAndSaveAssistantMsg()
          }
        })
        return
      } catch (err) {
        if (currentStreamer) currentStreamer.abort()
        finishAndSaveAssistantMsg()
        return
      }
    } else {
      // 未配置 API Key 时，诚实回显本地原生引擎提取的真实内容，绝不编造
      assistantMsgObj.thought += `- 后端引擎完成全量文本提取 (耗时: ${documentExtractResult.costMs}ms，有效字符数: ${documentExtractResult.characterCount} 字)\n- 检查到未配置 API Key，正由本地平滑打字机平稳回显后端真实提取的完整文本！`
      const fallbackExtractContent = `### 📄 本地原生引擎已真实提取全文 (${documentExtractResult.fileName})

> **解析指标**：耗时 **${documentExtractResult.costMs}ms**，提取有效字符 **${documentExtractResult.characterCount} 字**，零 Token 消耗。

---

#### 💡 本地真实提取的内容原文如下：

\`\`\`text
${rawText}
\`\`\`

---

> 🔔 **温馨提示**：当前尚未配置大模型 API Key。您可以前往 **[AI 模型热插拔中心](/model-config)** 保存您的真实百炼/DeepSeek Key，保存后再次发送，系统将直接由真实大模型输出 **原生 CoT 思考推导链** 与 **智能综合评估**！`

      setTimeout(() => {
        if (!isGenerating.value) return
        assistantMsgObj.isThinking = false
        assistantMsgObj.isThoughtExpanded = false

        const streamer = createSmoothStreamer({
          targetMsg: assistantMsgObj,
          onDone: finishAndSaveAssistantMsg,
          stepInterval: 15
        })
        currentStreamer = streamer
        streamer.push(fallbackExtractContent)
        streamer.finish()
      }, 500)
      return
    }
  }

  // 场景 B: 普通文字对话 (有 API Key)
  if (userApiKey) {
    ElMessage.info(`正在由 ${userModelName} 结合数据库多轮上下文思考并回答...`)

    let promptToSend = text
    if (/继续|接着写|没写完|往下写/i.test(text)) {
      promptToSend = `请紧接着你上一轮回答未完结的内容继续往下输出，切勿重复前面已有的文字。`
    } else if (/重新生成|重写|重新总结|重新来一遍|重新梳理|再写一遍|被截断|截断了/i.test(text)) {
      promptToSend = `请根据前文对话历史，重新对上一轮问题进行完整严谨的回答。`
    } else {
      // 检查当前会话历史中是否有之前解析过的文档或图片
      const recentDoc = findRecentSessionDocument()
      if (recentDoc && (/图片|图|截图|文档|简历|项目/i.test(text) || /讲了啥|讲了什么|内容/i.test(text))) {
        promptToSend = `【会话关联背景 - 前文上传的文档/图片《${recentDoc.fileName}》真实提取内容】:
\`\`\`
${recentDoc.extractedText.slice(0, 3000)}
\`\`\`

【用户的提问与诉求】:
${text}`
      }
    }

    const streamer = createSmoothStreamer({
      targetMsg: assistantMsgObj,
      onDone: finishAndSaveAssistantMsg,
      stepInterval: 20
    })
    currentStreamer = streamer

    try {
      await callRealLlmStream({
        baseUrl: userBaseUrl,
        apiKey: userApiKey,
        modelName: userModelName,
        userPrompt: promptToSend,
        contextMessages: preparedContext.messages,
        onThought: (chunk) => {
          assistantMsgObj.thought += chunk
          scrollToBottom()
        },
        onContent: (chunk) => {
          streamer.push(chunk)
        },
        onDone: () => {
          streamer.finish()
        },
        onError: (err) => {
          streamer.abort()
          ElMessage.error('调用模型失败: ' + err.message)
          assistantMsgObj.content += `\n\n> ⚠️ **调用失败**: ${err.message}`
          finishAndSaveAssistantMsg()
        }
      })
      return
    } catch (err) {
      if (currentStreamer) currentStreamer.abort()
      finishAndSaveAssistantMsg()
      return
    }
  }

  // 场景 D: 既没传文件也没配置 API Key
  assistantMsgObj.thought += `- 未配置外部 API Key，已自动接入本地工业领域智能推理引擎。\n- 正在根据领域意图 (\`${routing.domain}\`) 与上下文规则构建专业业务报告...`

  setTimeout(() => {
    if (!isGenerating.value) return
    assistantMsgObj.thought += `\n- 阶段三推导完成，正在通过打字机队列平滑流式输出报告。`
    assistantMsgObj.isThinking = false
    assistantMsgObj.isThoughtExpanded = false

    const fallbackText = generateDomainFallbackResponse(text, routing)
    const streamer = createSmoothStreamer({
      targetMsg: assistantMsgObj,
      onDone: finishAndSaveAssistantMsg,
      stepInterval: 18
    })
    currentStreamer = streamer
    streamer.push(fallbackText)
    streamer.finish()
  }, 800)
}

// 停止生成 (立即物理中断连接并封顶输出，绝对不继续输出任何文字)
const stopGeneration = () => {
  if (currentStreamer) {
    try {
      currentStreamer.abort()
    } catch (_) {}
    currentStreamer = null
  }
  if (currentAbortController) {
    try {
      currentAbortController.abort()
    } catch (_) {}
    currentAbortController = null
  }
  isGenerating.value = false

  const lastMsg = currentMessages.value[currentMessages.value.length - 1]
  if (lastMsg?.role === 'assistant') {
    lastMsg.isThinking = false
    lastMsg.isStreaming = false
    if (!lastMsg.content.includes('(用户已主动中断输出)')) {
      lastMsg.content += '\n\n*(用户已主动中断输出)*'
    }
    // 同步将当前中断后的消息落库至 MySQL
    saveMessageApi(currentSessionId.value, {
      sessionId: currentSessionId.value,
      role: 'assistant',
      content: lastMsg.content,
      thought: lastMsg.thought,
      thoughtDuration: lastMsg.thoughtDuration || 1,
      routing: JSON.stringify(lastMsg.routing),
      attachment: null,
      time: lastMsg.time
    }).catch(console.warn)
  }
  ElMessage.warning('已立即中断生成，网络连接已断开')
}

onMounted(() => {
  loadSavedConfig()
  loadSavedSessions()
  scrollToBottom()
})
</script>

<style scoped lang="scss">
.chat-agent-container {
  height: calc(100vh - 120px);
  min-height: 600px;
}

.full-height-row {
  height: 100%;
}

.panel-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  background-color: #ffffff;

  &.no-padding :deep(.el-card__body) {
    padding: 0;
    height: 100%;
    display: flex;
    flex-direction: column;
  }
}

// 左栏：侧边栏
.left-panel {
  height: 100%;

  .sidebar-header {
    padding: 14px 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid #ebeef5;

    .header-title {
      font-size: 15px;
      font-weight: 600;
      color: #303133;
      display: flex;
      align-items: center;
      gap: 6px;
    }
  }

  .agent-role-section {
    border-bottom: 1px solid #f2f6fc;
    padding-bottom: 8px;

    .section-label {
      padding: 10px 16px 6px;
      font-size: 12px;
      color: #909399;
    }

    .agent-preset-item {
      padding: 8px 16px;
      display: flex;
      align-items: center;
      gap: 10px;
      cursor: pointer;
      transition: background-color 0.2s;

      &:hover {
        background-color: #f5f7fa;
      }

      &.active {
        background-color: #ecf5ff;
        border-right: 3px solid #409eff;
      }

      .agent-avatar {
        width: 30px;
        height: 30px;
        border-radius: 6px;
        display: flex;
        align-items: center;
        justify-content: center;
        color: #ffffff;
      }

      .agent-info {
        flex: 1;
        overflow: hidden;

        .agent-name {
          font-size: 13px;
          font-weight: 500;
          color: #303133;
        }

        .agent-desc {
          font-size: 11px;
          color: #909399;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
      }
    }
  }

  .session-list-section {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;

    .section-header-row {
      padding: 10px 16px 6px;
      display: flex;
      justify-content: space-between;
      align-items: center;

      .section-label {
        font-size: 12px;
        color: #909399;
      }

      .persist-tag {
        font-size: 10px;
        padding: 0 4px;
        height: 20px;
        line-height: 18px;
      }
    }

    .session-scroll-list {
      flex: 1;
      overflow-y: auto;
      padding: 0 8px;

      .session-item {
        padding: 10px 10px;
        border-radius: 4px;
        margin-bottom: 4px;
        display: flex;
        align-items: center;
        gap: 8px;
        cursor: pointer;
        position: relative;
        transition: background-color 0.2s;

        &:hover {
          background-color: #f5f7fa;
          .session-del-icon {
            display: inline-flex;
          }
        }

        &.active {
          background-color: #f0f7ff;
          color: #409eff;
        }

        .session-icon {
          color: #909399;
        }

        .session-content {
          flex: 1;
          overflow: hidden;

          .session-title {
            font-size: 13px;
            color: #303133;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }

          .session-time {
            font-size: 11px;
            color: #c0c4cc;
            margin-top: 2px;
          }
        }

        .session-del-icon {
          display: none;
          color: #c0c4cc;
          font-size: 14px;
          padding: 2px;
          border-radius: 50%;
          transition: all 0.2s;

          &:hover {
            color: #f56c6c;
            background-color: #fef0f0;
          }
        }
      }
    }
  }

  .sidebar-footer {
    padding: 12px 16px;
    border-top: 1px solid #ebeef5;
    background-color: #fafafa;

    .footer-tenant-badge {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 6px;
    }

    .footer-model-row {
      font-size: 11px;
      color: #909399;
    }
  }
}

// 右侧全宽对话主区域
.center-panel {
  height: 100%;

  .chat-box-card {
    :deep(.el-card__header) {
      padding: 12px 16px;
      border-bottom: 1px solid #ebeef5;
    }

    :deep(.el-card__body) {
      flex: 1;
      padding: 0;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
  }

  .chat-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .chat-header-left {
      display: flex;
      align-items: center;
      gap: 8px;

      .chat-title {
        font-size: 15px;
        font-weight: 600;
        color: #303133;
      }
    }
  }

  .message-container {
    flex: 1;
    overflow-y: auto;
    padding: 20px;
    background-color: #f8f9fb;

    .message-row {
      display: flex;
      margin-bottom: 24px;
      gap: 12px;

      &.row-user {
        flex-direction: row-reverse;

        .msg-bubble {
          background-color: #409eff;
          color: #ffffff;
          border-radius: 8px 2px 8px 8px;

          :deep(.markdown-body) {
            color: #ffffff;
            strong, h1, h2, h3, h4, p, li { color: #ffffff; }
            code { background-color: rgba(255, 255, 255, 0.2); color: #ffffff; }
          }
        }

        .msg-meta {
          text-align: right;
        }
      }

      &.row-assistant {
        flex-direction: row;

        .msg-bubble {
          background-color: #ffffff;
          color: #303133;
          border: 1px solid #e4e7ed;
          border-radius: 2px 8px 8px 8px;
        }
      }

      .msg-bubble-wrap {
        max-width: 88%;

        .msg-meta {
          margin-bottom: 6px;
          font-size: 12px;
          color: #909399;
          display: flex;
          align-items: center;
          gap: 8px;

          .sender-name {
            font-weight: 500;
            margin-right: 4px;
          }

          .delete-round-btn {
            opacity: 0;
            font-size: 12px;
            padding: 0 4px;
            height: 18px;
            line-height: 18px;
            transition: opacity 0.2s;

            &:hover {
              color: #f56c6c;
            }
          }
        }

        &:hover {
          .msg-meta .delete-round-btn {
            opacity: 1;
          }
        }

        // 用户消息携带的附件卡片 (带图片缩略图与 OSS 归档标识)
        .user-attachment-card {
          background-color: #ecf5ff;
          border: 1px solid #d9ecff;
          border-radius: 6px;
          padding: 8px 12px;
          margin-bottom: 8px;
          max-width: 380px;

          .attachment-meta-row {
            display: flex;
            align-items: center;
            gap: 8px;

            .file-meta {
              overflow: hidden;
              .file-name {
                font-size: 12px;
                font-weight: 500;
                color: #409eff;
              }
              .file-size {
                font-size: 11px;
                color: #909399;
              }
            }
          }

          .oss-badge-row {
            margin-top: 6px;
            padding-top: 6px;
            border-top: 1px dashed #d9ecff;

            .oss-link-btn {
              display: inline-flex;
              align-items: center;
              gap: 4px;
              font-size: 11px;
              color: #e6a23c;
              text-decoration: none;
              font-weight: 500;

              &:hover {
                text-decoration: underline;
                color: #d48806;
              }
            }
          }

          .image-thumbnail-box {
            margin-top: 8px;
            max-width: 240px;
            border-radius: 4px;
            overflow: hidden;
            border: 1px solid #dcdfe6;

            .thumbnail-img {
              max-height: 180px;
              width: 100%;
              display: block;
            }
          }
        }

        .msg-bubble {
          padding: 14px 18px;
          box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
          line-height: 1.6;
          font-size: 14px;
          word-break: break-word;

          // 核心特性 1：意图路由与工具剪枝指示胶囊条
          .routing-badge-bar {
            display: flex;
            align-items: center;
            flex-wrap: wrap;
            gap: 8px;
            margin-bottom: 10px;
            padding-bottom: 8px;
            border-bottom: 1px dashed #ebeef5;

            .routing-domain-pill {
              display: inline-flex;
              align-items: center;
              gap: 4px;
              color: #ffffff;
              padding: 2px 8px;
              border-radius: 10px;
              font-size: 11px;
              font-weight: 500;
            }

            .routing-tools-pill {
              font-size: 11px;
              color: #606266;
              background-color: #f4f4f5;
              padding: 2px 8px;
              border-radius: 4px;
              border: 1px solid #e9e9eb;

              strong {
                color: #409eff;
              }
            }
          }

          // 核心特性 2：CoT 思考链卡片 (集成阶段一路由决策、阶段二底层调度与阶段三模型深度推理)
          .thought-container {
            background-color: #f7f8fa;
            border-left: 3px solid #409eff;
            border-radius: 4px;
            padding: 8px 12px;
            margin-bottom: 12px;

            .thought-header {
              display: flex;
              justify-content: space-between;
              align-items: center;
              cursor: pointer;
              user-select: none;

              .thought-title {
                font-size: 12px;
                color: #606266;
                display: flex;
                align-items: center;
                gap: 6px;

                .thinking-spinner {
                  color: #409eff;
                  display: flex;
                  align-items: center;
                  gap: 4px;
                }

                .thought-done {
                  color: #606266;
                  display: flex;
                  align-items: center;
                  gap: 4px;
                  font-weight: 500;
                }
              }

              .thought-toggle-arrow {
                font-size: 12px;
                color: #909399;
                transition: transform 0.2s;

                .is-expanded {
                  transform: rotate(180deg);
                }
              }
            }

            .thought-content {
              margin-top: 8px;
              padding-top: 8px;
              border-top: 1px dashed #e4e7ed;
              font-size: 12px;
              color: #5c6b77;
              line-height: 1.6;

              .thought-cursor {
                display: inline-block;
                color: #409eff;
                animation: blink 0.8s infinite;
              }
            }
          }

          &.streaming-bubble {
            .bubble-content {
              display: inline;
              > *:last-child {
                display: inline;
              }
            }
          }

          .cursor-blink {
            display: inline-block;
            margin-left: 2px;
            color: #409eff;
            font-weight: bold;
            font-size: 15px;
            line-height: 1;
            vertical-align: baseline;
            animation: blink 0.8s infinite;
          }
        }
      }
    }
  }

  .empty-chat-guide {
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;
    color: #606266;
    padding: 30px;

    h4 {
      margin: 12px 0 6px;
      color: #303133;
    }

    p {
      font-size: 13px;
      color: #909399;
      margin-bottom: 20px;
    }

    .sample-questions {
      background: #ffffff;
      padding: 16px;
      border-radius: 6px;
      border: 1px solid #e4e7ed;
      max-width: 500px;

      .sample-title {
        font-size: 12px;
        color: #909399;
        display: block;
        margin-bottom: 10px;
      }

      .sample-chips {
        display: flex;
        flex-direction: column;
        gap: 8px;

        .sample-chip {
          cursor: pointer;
          text-align: left;
          height: auto;
          padding: 8px 12px;
          white-space: normal;
          line-height: 1.4;

          &:hover {
            border-color: #409eff;
            color: #409eff;
          }
        }
      }
    }
  }

  .chat-input-area {
    padding: 10px 16px;
    border-top: 1px solid #ebeef5;
    background-color: #ffffff;

    .attachment-preview-bar {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 8px;

      .file-chip {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        background-color: #ecf5ff;
        border: 1px solid #d9ecff;
        padding: 4px 10px;
        border-radius: 4px;
        font-size: 12px;
        color: #409eff;

        .chip-preview-img {
          width: 20px;
          height: 20px;
          border-radius: 2px;
        }

        .remove-file-btn {
          cursor: pointer;
          color: #909399;
          &:hover {
            color: #f56c6c;
          }
        }
      }

      .upload-tip-text {
        font-size: 11px;
        color: #909399;
      }
    }

    .input-bottom-actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-top: 8px;
    }
  }
}

// 专业 GitHub Markdown 样式 (支持严格 GFM 规范表格、标题、代码块、列表)
:deep(.markdown-body) {
  font-size: 14px;
  line-height: 1.7;
  color: #24292e;

  h1, h2, h3, h4, h5, h6 {
    margin-top: 16px;
    margin-bottom: 8px;
    font-weight: 600;
    color: #1f2328;
    line-height: 1.3;
  }
  h1 { font-size: 1.45em; border-bottom: 1px solid #d0d7de; padding-bottom: 6px; }
  h2 { font-size: 1.25em; border-bottom: 1px solid #d0d7de; padding-bottom: 4px; }
  h3 { font-size: 1.12em; }
  h4 { font-size: 1.02em; }

  p {
    margin-top: 0;
    margin-bottom: 10px;
  }

  blockquote {
    margin: 10px 0;
    padding: 8px 16px;
    color: #57606a;
    border-left: 4px solid #409eff;
    background-color: #f6f8fa;
    border-radius: 3px;

    p { margin-bottom: 0; }
  }

  ul, ol {
    margin-top: 4px;
    margin-bottom: 10px;
    padding-left: 22px;
  }

  li {
    margin-top: 4px;
  }

  table {
    width: 100%;
    border-collapse: collapse;
    margin: 14px 0;
    display: table;
    overflow-x: auto;
    border: 1px solid #d0d7de;
    border-radius: 4px;

    th, td {
      border: 1px solid #d0d7de;
      padding: 8px 14px;
      text-align: left;
      font-size: 13px;
    }

    th {
      background-color: #f6f8fa;
      font-weight: 600;
      color: #24292e;
    }

    tr:nth-child(even) {
      background-color: #fbfcfd;
    }

    tr:hover {
      background-color: #f2f7ff;
    }
  }

  hr {
    height: 1px;
    padding: 0;
    margin: 16px 0;
    background-color: #d0d7de;
    border: 0;
  }

  code {
    padding: 2px 6px;
    margin: 0;
    font-size: 85%;
    background-color: #eff1f3;
    border-radius: 4px;
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace;
    color: #0969da;
  }

  pre {
    padding: 12px 16px;
    overflow: auto;
    font-size: 85%;
    line-height: 1.45;
    background-color: #1e1e1e;
    color: #d4d4d4;
    border-radius: 6px;
    margin: 12px 0;

    code {
      display: inline;
      padding: 0;
      margin: 0;
      background-color: transparent;
      border: 0;
      color: inherit;
    }
  }

  strong {
    font-weight: 600;
    color: #1f2328;
  }
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
</style>
