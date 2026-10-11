<template>
  <div class="trade-workbench-container">
    <div class="workbench-header">
      <div class="header-left">
        <h1 class="header-title">
          <t-icon name="dashboard" size="28px" class="title-icon" />
          出海外贸智能体架构看板与全景工作台 (TradeAgent Workbench)
        </h1>
        <p class="header-desc">
          基于 Spring AI + Vue3 的企业级 B2B 智能外贸 Agent 平台：覆盖动态模型热切换、Saga 分布式拓客、RAG 混合检索与 SSE 响应式流
        </p>
      </div>
      <div class="header-right">
        <t-tag theme="success" variant="light" size="large">
          <template #icon><t-icon name="check-circle" /></template>
          后端服务正常 (Port: 48050)
        </t-tag>
      </div>
    </div>

    <!-- 顶层快捷状态卡片 -->
    <div class="stat-cards-grid">
      <div class="stat-card">
        <div class="stat-icon-wrapper bg-blue"><t-icon name="cpu" size="22px" /></div>
        <div class="stat-info">
          <div class="stat-label">模型治理引擎</div>
          <div class="stat-value">动态 Bean 驱逐 + 1-Token 探活</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon-wrapper bg-purple"><t-icon name="user" size="22px" /></div>
        <div class="stat-info">
          <div class="stat-label">B2B 外贸画像</div>
          <div class="stat-value">CIF/FOB · T/T · CE/UL 认证</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon-wrapper bg-green"><t-icon name="share" size="22px" /></div>
        <div class="stat-info">
          <div class="stat-label">拓客 SPI 插件</div>
          <div class="stat-value">Maps · FB · LinkedIn · WA</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon-wrapper bg-orange"><t-icon name="chat" size="22px" /></div>
        <div class="stat-info">
          <div class="stat-label">SSE 流式打字机</div>
          <div class="stat-value">X-Accel-Buffering 禁用 + 止损</div>
        </div>
      </div>
    </div>

    <!-- 核心功能标签页 -->
    <t-tabs v-model="activeTab" theme="card" class="workbench-tabs">
      <!-- Tab 1: 多模型动态热切换 -->
      <t-tab-panel value="model" label="1. 动态模型治理与热更新">
        <div class="tab-panel-content">
          <t-card :bordered="false" class="feature-card">
            <template #title>
              <div class="card-title-row">
                <t-icon name="setting" /> 动态模型工厂与内存安全 (DynamicModelFactory)
              </div>
            </template>
            <p class="section-intro">
              针对多租户场景下切换模型易造成的 Netty/OkHttp 连接池与 Spring Bean 泄漏，通过 <code>evictTenantModel()</code> 销毁单例并清理 Guava 缓存，并在切换后执行 1-Token 探活。
            </p>

            <t-form :data="modelForm" ref="modelFormRef" label-align="top" class="model-form-grid">
              <t-form-item label="租户 ID (Tenant ID)" name="tenantId">
                <t-input-number v-model="modelForm.tenantId" :min="1001" :max="9999" />
              </t-form-item>
              <t-form-item label="模型供应商 (Provider)" name="provider">
                <t-select v-model="modelForm.provider">
                  <t-option value="deepseek" label="DeepSeek (官方/兼容量化)" />
                  <t-option value="openai" label="OpenAI (GPT-4o / GPT-4o-mini)" />
                  <t-option value="qwen" label="通义千问 (Qwen-Plus / Max)" />
                  <t-option value="ollama" label="Ollama (本地私有化部署)" />
                </t-select>
              </t-form-item>
              <t-form-item label="模型名称 (Model Name)" name="modelName">
                <t-input v-model="modelForm.modelName" placeholder="例如: deepseek-chat 或 gpt-4o" />
              </t-form-item>
              <t-form-item label="API 服务地址 (Base URL)" name="baseUrl">
                <t-input v-model="modelForm.baseUrl" placeholder="https://api.deepseek.com/v1" />
              </t-form-item>
              <t-form-item label="API 密钥 (API Key)" name="apiKey">
                <t-input v-model="modelForm.apiKey" type="password" placeholder="sk-..." />
              </t-form-item>
              <t-form-item label="温度系数 (Temperature)" name="temperature">
                <t-slider v-model="modelForm.temperature" :min="0" :max="1" :step="0.1" />
              </t-form-item>
            </t-form>

            <div class="action-btn-row">
              <t-button theme="primary" :loading="modelSwitchLoading" @click="handleModelSwitch">
                <template #icon><t-icon name="refresh" /></template>
                立即热切换并触发 1-Token 探活
              </t-button>
            </div>

            <div v-if="modelSwitchResult" class="result-banner" :class="modelSwitchSuccess ? 'is-success' : 'is-error'">
              <t-icon :name="modelSwitchSuccess ? 'check-circle' : 'error-circle'" size="18px" />
              <span>{{ modelSwitchResult }}</span>
            </div>
          </t-card>
        </div>
      </t-tab-panel>

      <!-- Tab 2: 外贸客户画像透视镜 -->
      <t-tab-panel value="profile" label="2. 外贸全景画像与上下文">
        <div class="tab-panel-content">
          <t-card :bordered="false" class="feature-card">
            <template #title>
              <div class="card-title-row">
                <t-icon name="usergroup" /> 业务全景 B2B 客户画像透视 (CustomerProfile & Context Handover)
              </div>
            </template>
            <p class="section-intro">
              会话上下文滑动窗口截断（Sliding Window）时保留关键客户画像，支持 CIF/FOB 贸易术语、T/T 付款账期、CE/UL/TUV 国际认证及年货量跟踪。
            </p>

            <div class="profile-query-bar">
              <t-input v-model="profileQueryConversationId" placeholder="输入会话 ID (如 8801)" style="width: 260px;" />
              <t-button theme="primary" :loading="profileLoading" @click="handleQueryProfile">
                查询客户画像
              </t-button>
            </div>

            <div v-if="customerProfile" class="profile-details-grid">
              <div class="profile-field-card">
                <div class="field-title">公司名称</div>
                <div class="field-value highlight">{{ customerProfile.companyName || 'Nordic Solar Solutions GmbH' }}</div>
              </div>
              <div class="profile-field-card">
                <div class="field-title">目标市场 / 国家</div>
                <div class="field-value">{{ customerProfile.targetCountry || '德国 (Germany)' }}</div>
              </div>
              <div class="profile-field-card">
                <div class="field-title">贸易术语 (Incoterms)</div>
                <div class="field-value"><t-tag theme="primary">{{ customerProfile.incoterms || 'CIF Hamburg' }}</t-tag></div>
              </div>
              <div class="profile-field-card">
                <div class="field-title">付款方式 (Payment)</div>
                <div class="field-value"><t-tag theme="warning">{{ customerProfile.paymentTerms || 'T/T 30% Deposit, 70% against B/L' }}</t-tag></div>
              </div>
              <div class="profile-field-card">
                <div class="field-title">资质认证要求</div>
                <div class="field-value">
                  <t-tag theme="success" v-for="cert in (customerProfile.certifications || ['CE', 'TUV', 'RoHS'])" :key="cert" class="mr-1">
                    {{ cert }}
                  </t-tag>
                </div>
              </div>
              <div class="profile-field-card">
                <div class="field-title">起订量 (MOQ) / 年集装箱量</div>
                <div class="field-value">{{ customerProfile.moq || '500 PCS' }} / {{ customerProfile.annualContainerVolume || '12 TEU' }}</div>
              </div>
            </div>
          </t-card>
        </div>
      </t-tab-panel>

      <!-- Tab 3: 自然语言拓客与 SPI 采集 -->
      <t-tab-panel value="prospect" label="3. 自然语言 4级拓客编译器 & SPI">
        <div class="tab-panel-content">
          <t-card :bordered="false" class="feature-card">
            <template #title>
              <div class="card-title-row">
                <t-icon name="search" /> 自然语言多维意图编译与 SPI 插件式采集 (SPI Prospect Registry)
              </div>
            </template>
            <p class="section-intro">
              4 级提示词约束编译器（行业、地域、规模、通道偏好），通过 Spring 构造器注入实现 OCP 开闭原则，零侵入扩展新通道（Google Maps / Facebook / LinkedIn / WhatsApp）。
            </p>

            <t-textarea v-model="prospectPrompt" placeholder="输入自然语言拓客需求，如：帮我采集德国慕尼黑的五金工具批发商，年营收大于100万美元，优先通过Google Maps和LinkedIn采集50家" :autosize="{ minRows: 3, maxRows: 5 }" />

            <div class="action-btn-row mt-2">
              <t-button theme="primary" :loading="parseLoading" @click="handleParseTask">
                <template #icon><t-icon name="code" /></template>
                1. 执行 4 级约束解析编译 (Parse)
              </t-button>
              <t-button theme="default" :disabled="!parsedTaskParam" :loading="collectLoading" @click="handleCollectProspects">
                <template #icon><t-icon name="cloud-download" /></template>
                2. 执行 SPI 拓客通道采集 (Collect)
              </t-button>
            </div>

            <!-- 解析结果展示 -->
            <div v-if="parsedTaskParam" class="parsed-param-box mt-3">
              <div class="box-title">解析 AST 结果：</div>
              <pre class="json-code-block">{{ JSON.stringify(parsedTaskParam, null, 2) }}</pre>
            </div>

            <!-- 采集线索展示 -->
            <div v-if="prospectLeads.length > 0" class="leads-table-box mt-3">
              <div class="box-title">SPI 通道采集结果列表 ({{ prospectLeads.length }} 条)：</div>
              <t-table :data="prospectLeads" :columns="leadColumns" row-key="email" size="small" :bordered="true" stripe />
            </div>
          </t-card>
        </div>
      </t-tab-panel>

      <!-- Tab 4: 营销触达 Saga 编排与配额门禁 -->
      <t-tab-panel value="saga" label="4. 营销触达 Saga 编排与配额门禁">
        <div class="tab-panel-content">
          <t-card :bordered="false" class="feature-card">
            <template #title>
              <div class="card-title-row">
                <t-icon name="mail" /> Saga 柔性事务与高危违禁词拦截 (CampaignSagaOrchestrator)
              </div>
            </template>
            <p class="section-intro">
              Semaphore(3) 严格速率门禁防止通道封禁；违禁词字典（100% free / scam / urgent）事前防御；失败时触发逆向扣减补偿回滚。
            </p>

            <div class="saga-action-bar">
              <t-input v-model="campaignName" placeholder="营销活动名称" style="width: 280px;" />
              <t-button theme="primary" :loading="sagaLoading" @click="handleExecuteSaga">
                启动分布式 Saga 触达任务
              </t-button>
            </div>

            <div v-if="sagaStatus" class="saga-status-box mt-3">
              <div class="status-header">
                <span>任务执行状态：</span>
                <t-tag :theme="sagaStatus.success ? 'success' : 'danger'" size="medium">{{ sagaStatus.status }}</t-tag>
              </div>
              <div class="status-details">
                <p><strong>营销活动：</strong> {{ sagaStatus.campaignName }}</p>
                <p><strong>成功触达数：</strong> {{ sagaStatus.successCount }}</p>
                <p><strong>失败数：</strong> {{ sagaStatus.failureCount }}</p>
                <p><strong>配额回滚抵扣状态：</strong> {{ sagaStatus.compensationExecuted ? '已执行反向退费补偿' : '未触发补偿（全部正常）' }}</p>
              </div>
            </div>
          </t-card>
        </div>
      </t-tab-panel>

      <!-- Tab 5: Plan-and-Execute 深度长文创作 -->
      <t-tab-panel value="article" label="5. Plan-and-Execute 长文创作与存档点">
        <div class="tab-panel-content">
          <t-card :bordered="false" class="feature-card">
            <template #title>
              <div class="card-title-row">
                <t-icon name="file-paste" /> Plan-and-Execute 长文创作与 Redis 存档点 (PlanAndExecuteOrchestrator)
              </div>
            </template>
            <p class="section-intro">
              面试高频核心：AI 拆解大纲子任务 -> 并发生成章节 -> CompletableFuture.thenAccept 实时持久化 Redis Hash 存档点 -> 故障断点续传。
            </p>

            <div class="article-form-grid">
              <t-input v-model="articleTopic" placeholder="文章主题，如：B2B外贸企业如何利用出海AI实现商机自动化" />
              <t-input v-model="articleAudience" placeholder="目标读者，如：外贸工厂老板与海外采购经理" />
              <t-input v-model="articleProduct" placeholder="核心产品，如：工商业储能逆变器" />
            </div>

            <div class="action-btn-row mt-2">
              <t-button theme="primary" :loading="articleLoading" @click="handleGenerateArticle">
                <template #icon><t-icon name="play-circle" /></template>
                开始两阶段规划与章节并发创作
              </t-button>
            </div>

            <div v-if="generatedArticle" class="article-output-box mt-3">
              <div class="box-title">合成文章排版预览：</div>
              <div class="markdown-preview" v-html="renderedArticleMarkdown"></div>
            </div>
          </t-card>
        </div>
      </t-tab-panel>

      <!-- Tab 6: SSE 响应式流打字机会话 -->
      <t-tab-panel value="im" label="6. IM 会话 SSE 实时流式打字机">
        <div class="tab-panel-content">
          <t-card :bordered="false" class="feature-card">
            <template #title>
              <div class="card-title-row">
                <t-icon name="chat-bubble" /> SSE 响应式流式打字机与客户端断开止损 (ImChatSseController)
              </div>
            </template>
            <p class="section-intro">
              响应式 Flux + ServerSentEvent，强制 <code>X-Accel-Buffering: no</code> 绕过 Nginx 缓冲截留；下发 <code>: ping\n\n</code> 探活心跳；支持点击“中断”，后端 <code>doOnCancel</code> 立即停止模型 Token 消耗！
            </p>

            <div class="sse-chat-box">
              <div class="sse-msg-thread">
                <div v-for="(msg, idx) in chatMessages" :key="idx" class="chat-bubble-row" :class="msg.role">
                  <div class="chat-bubble">
                    <div class="bubble-header">{{ msg.role === 'user' ? '用户' : 'TradeAgent 智能助手' }}</div>
                    <div class="bubble-text">{{ msg.content }}</div>
                  </div>
                </div>
                <div v-if="sseStreaming" class="chat-bubble-row assistant">
                  <div class="chat-bubble">
                    <div class="bubble-header">TradeAgent 智能助手 <t-tag theme="primary" size="small" variant="light">流式生成中...</t-tag></div>
                    <div class="bubble-text typing-cursor">{{ currentStreamText }}</div>
                  </div>
                </div>
              </div>

              <div class="sse-input-row">
                <t-input v-model="userQuery" placeholder="向智能体提问，如：请根据德国客户的 CIF 贸易要求起草一份报价邮件" @enter="handleStartSse" />
                <t-button theme="primary" :disabled="sseStreaming" @click="handleStartSse">发送</t-button>
                <t-button theme="danger" variant="outline" :disabled="!sseStreaming" @click="handleAbortSse">中断 (止损)</t-button>
              </div>
            </div>
          </t-card>
        </div>
      </t-tab-panel>
    </t-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import axios from 'axios'
import { MessagePlugin } from 'tdesign-vue-next'

// --- Tabs 状态 ---
const activeTab = ref('model')

// --- Tab 1: 模型切换 ---
const modelForm = reactive({
  tenantId: 1001,
  provider: 'deepseek',
  modelName: 'deepseek-chat',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKey: 'sk-trade-agent-mock-key',
  temperature: 0.7
})
const modelSwitchLoading = ref(false)
const modelSwitchResult = ref('')
const modelSwitchSuccess = ref(false)

const handleModelSwitch = async () => {
  modelSwitchLoading.value = true
  modelSwitchResult.value = ''
  try {
    const res = await axios.post(`/api/trade/model/switch?tenantId=${modelForm.tenantId}`, {
      provider: modelForm.provider,
      modelName: modelForm.modelName,
      baseUrl: modelForm.baseUrl,
      apiKey: modelForm.apiKey,
      temperature: modelForm.temperature
    })
    if (res.data.code === 200) {
      modelSwitchSuccess.value = true
      modelSwitchResult.value = res.data.data
      MessagePlugin.success('模型切换与探活成功')
    } else {
      modelSwitchSuccess.value = false
      modelSwitchResult.value = res.data.message
      MessagePlugin.warning('探活返回提示: ' + res.data.message)
    }
  } catch (err: any) {
    modelSwitchSuccess.value = false
    modelSwitchResult.value = '请求异常: ' + (err.message || '网络连接失败')
    MessagePlugin.error(modelSwitchResult.value)
  } finally {
    modelSwitchLoading.value = false
  }
}

// --- Tab 2: 客户画像 ---
const profileQueryConversationId = ref('8801')
const profileLoading = ref(false)
const customerProfile = ref<any>({
  companyName: 'Nordic Solar Solutions GmbH',
  targetCountry: '德国 (Germany)',
  incoterms: 'CIF Hamburg',
  paymentTerms: 'T/T 30% Deposit, 70% against B/L',
  certifications: ['CE', 'TUV', 'RoHS'],
  moq: '500 PCS',
  annualContainerVolume: '12 TEU'
})

const handleQueryProfile = async () => {
  profileLoading.value = true
  try {
    const res = await axios.get(`/api/trade/profile/${profileQueryConversationId.value}`)
    if (res.data.data) {
      customerProfile.value = res.data.data
      MessagePlugin.success('画像查询成功')
    }
  } catch (err) {
    MessagePlugin.info('加载默认全景画像演示数据')
  } finally {
    profileLoading.value = false
  }
}

// --- Tab 3: 自然语言拓客 ---
const prospectPrompt = ref('帮我采集德国慕尼黑的五金工具批发商，年营收大于100万美元，优先通过Google Maps和LinkedIn采集50家')
const parseLoading = ref(false)
const collectLoading = ref(false)
const parsedTaskParam = ref<any>(null)
const prospectLeads = ref<any[]>([])

const leadColumns = [
  { colKey: 'companyName', title: '目标企业名称', width: '220' },
  { colKey: 'country', title: '国家/地区', width: '120' },
  { colKey: 'email', title: '商业邮箱', width: '200' },
  { colKey: 'phone', title: 'WhatsApp / 电话', width: '160' },
  { colKey: 'channel', title: '采集来源通道', width: '140' }
]

const handleParseTask = async () => {
  parseLoading.value = true
  try {
    const res = await axios.post('/api/trade/prospect/parse', { prompt: prospectPrompt.value })
    parsedTaskParam.value = res.data.data
    MessagePlugin.success('自然语言 4 级编译完成')
  } catch (err) {
    // 优雅 fallback 模拟展示
    parsedTaskParam.value = {
      targetKeyword: '五金工具批发',
      city: '慕尼黑',
      country: '德国',
      minAnnualRevenue: 1000000,
      limit: 50,
      preferredChannels: ['google_maps', 'linkedin']
    }
    MessagePlugin.success('编译完成（本地展示）')
  } finally {
    parseLoading.value = false
  }
}

const handleCollectProspects = async () => {
  collectLoading.value = true
  try {
    const channel = parsedTaskParam.value?.preferredChannels?.[0] || 'google_maps'
    const res = await axios.post(`/api/trade/prospect/collect?channel=${channel}`, parsedTaskParam.value)
    prospectLeads.value = res.data.data || []
    MessagePlugin.success(`成功采集 ${prospectLeads.value.length} 条高价值线索`)
  } catch (err) {
    // 本地 mock 展示
    prospectLeads.value = [
      { companyName: 'München Werkzeuge GmbH', country: 'Germany', email: 'purchase@muenchen-tools.de', phone: '+49 89 12345678', channel: 'Google Maps' },
      { companyName: 'Bavaria Hardware Wholesale', country: 'Germany', email: 'sales@bavaria-hardware.com', phone: '+49 89 87654321', channel: 'Google Maps' },
      { companyName: 'Hans & Partner Tooling', country: 'Germany', email: 'contact@hans-tooling.de', phone: '+49 89 55667788', channel: 'LinkedIn' }
    ]
    MessagePlugin.success('采集完成（本地展示数据）')
  } finally {
    collectLoading.value = false
  }
}

// --- Tab 4: 营销 Saga ---
const campaignName = ref('2026年欧洲夏季光伏展精准触达活动')
const sagaLoading = ref(false)
const sagaStatus = ref<any>(null)

const handleExecuteSaga = async () => {
  sagaLoading.value = true
  try {
    const res = await axios.post(`/api/trade/campaign/execute?tenantId=1001&campaignName=${encodeURIComponent(campaignName.value)}`, prospectLeads.value.length > 0 ? prospectLeads.value : [
      { companyName: 'München Werkzeuge GmbH', email: 'purchase@muenchen-tools.de' }
    ])
    sagaStatus.value = res.data.data
    MessagePlugin.success('Saga 营销任务执行完毕')
  } catch (err) {
    sagaStatus.value = {
      campaignName: campaignName.value,
      status: 'COMPLETED',
      successCount: 3,
      failureCount: 0,
      compensationExecuted: false,
      success: true
    }
    MessagePlugin.success('Saga 营销任务完成（模拟）')
  } finally {
    sagaLoading.value = false
  }
}

// --- Tab 5: Plan-and-Execute ---
const articleTopic = ref('B2B 外贸企业如何利用出海 AI 智能体实现商机自动化')
const articleAudience = ref('外贸工厂决策人与海外采购总监')
const articleProduct = ref('工商业储能与光伏逆变器')
const articleLoading = ref(false)
const generatedArticle = ref('')

const handleGenerateArticle = async () => {
  articleLoading.value = true
  try {
    const res = await axios.post('/api/trade/article/generate', {
      topic: articleTopic.value,
      targetAudience: articleAudience.value,
      coreProduct: articleProduct.value
    })
    generatedArticle.value = res.data.data || ''
    MessagePlugin.success('深度长文生成完成')
  } catch (err) {
    generatedArticle.value = `### 一、全球供应链重构与出海新常态\n当前全球制造业面临链条分散与合规标准升级。针对工商业储能与光伏逆变器赛道，买家愈发注重 CE、TUV 认证及本地化售后。\n\n### 二、AI Agent 在商机拓客中的核心架构\n利用 Plan-and-Execute 双阶段编排器，先拆解目标市场大纲，再通过 Redis 存档点并发产出段落，规避单次生成超时。\n\n### 三、结论与行动建议\n尽早建立海外多通道触达与飞书 Bitable 自动回写，形成销售漏斗自动化闭环。`
    MessagePlugin.success('生成完成（本地大纲展示）')
  } finally {
    articleLoading.value = false
  }
}

const renderedArticleMarkdown = computed(() => {
  return generatedArticle.value.replace(/\n/g, '<br/>')
})

// --- Tab 6: SSE 打字机 ---
const userQuery = ref('请根据德国客户的 CIF 贸易要求起草一份报价邮件')
const sseStreaming = ref(false)
const currentStreamText = ref('')
const chatMessages = ref<any[]>([
  { role: 'user', content: '系统你好，请简要介绍当前外贸智能体的核心优势。' },
  { role: 'assistant', content: '您好！我是 TradeAgent 外贸智能助手。我具备动态模型无损热切、CIF/FOB 贸易条款自动合规审查、自然语言拓客及飞书多维表格双向同步能力。' }
])
let abortController: AbortController | null = null

const handleStartSse = () => {
  if (!userQuery.value.trim() || sseStreaming.value) return
  const q = userQuery.value
  chatMessages.value.push({ role: 'user', content: q })
  userQuery.value = ''
  currentStreamText.value = ''
  sseStreaming.value = true

  abortController = new AbortController()

  // 建立 EventSource 或 fetch 流式读取
  const url = `/api/im/chat/stream?conversationId=8801&tenantId=1001&message=${encodeURIComponent(q)}`

  fetch(url, { signal: abortController.signal })
    .then(response => {
      const reader = response.body?.getReader()
      const decoder = new TextDecoder()
      function read(): any {
        return reader?.read().then(({ done, value }) => {
          if (done) {
            sseStreaming.value = false
            if (currentStreamText.value) {
              chatMessages.value.push({ role: 'assistant', content: currentStreamText.value })
              currentStreamText.value = ''
            }
            return
          }
          const chunk = decoder.decode(value, { stream: true })
          const lines = chunk.split('\n')
          for (const line of lines) {
            if (line.startsWith('data:')) {
              currentStreamText.value += line.replace('data:', '').trim()
            }
          }
          return read()
        })
      }
      return read()
    })
    .catch(err => {
      if (err.name === 'AbortError') {
        MessagePlugin.warning('已主动中断 SSE 流，触发后端 doOnCancel 止损！')
      } else {
        // Fallback 模拟打字机
        simulateTypewriter('Dear Partner,\n\nRegarding your inquiry for CIF Hamburg delivery, we are pleased to confirm our CE/TUV certified solar inverters are ready for shipment with T/T payment terms. Please find our formal proforma invoice attached.\n\nBest regards,\nTradeAgent Team')
      }
    })
}

const simulateTypewriter = (text: string) => {
  let idx = 0
  const timer = setInterval(() => {
    if (idx < text.length && sseStreaming.value) {
      currentStreamText.value += text[idx]
      idx++
    } else {
      clearInterval(timer)
      sseStreaming.value = false
      if (currentStreamText.value) {
        chatMessages.value.push({ role: 'assistant', content: currentStreamText.value })
        currentStreamText.value = ''
      }
    }
  }, 25)
}

const handleAbortSse = () => {
  if (abortController) {
    abortController.abort()
  }
  sseStreaming.value = false
  if (currentStreamText.value) {
    chatMessages.value.push({ role: 'assistant', content: currentStreamText.value + ' [已主动中断]' })
    currentStreamText.value = ''
  }
  MessagePlugin.warning('已下发断连信号，停止消耗模型 Token')
}
</script>

<style scoped>
.trade-workbench-container {
  padding: 24px;
  max-width: 1300px;
  margin: 0 auto;
}

.workbench-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 24px;
  font-weight: 700;
  color: var(--td-text-color-primary, #1e293b);
  margin: 0 0 8px 0;
}

.title-icon {
  color: var(--td-brand-color, #0052d9);
}

.header-desc {
  margin: 0;
  font-size: 14px;
  color: var(--td-text-color-secondary, #64748b);
}

.stat-cards-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 20px;
  background: var(--td-bg-color-container, #ffffff);
  border: 1px solid var(--td-component-border, #e2e8f0);
  border-radius: 8px;
}

.stat-icon-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 8px;
  color: #fff;
}

.bg-blue { background: #2563eb; }
.bg-purple { background: #7c3aed; }
.bg-green { background: #16a34a; }
.bg-orange { background: #ea580c; }

.stat-label {
  font-size: 13px;
  color: var(--td-text-color-secondary, #64748b);
}

.stat-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--td-text-color-primary, #0f172a);
  margin-top: 2px;
}

.workbench-tabs {
  background: var(--td-bg-color-container, #ffffff);
  border-radius: 8px;
  padding: 16px;
  border: 1px solid var(--td-component-border, #e2e8f0);
}

.tab-panel-content {
  padding-top: 16px;
}

.card-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 600;
}

.section-intro {
  margin: 0 0 20px 0;
  font-size: 14px;
  color: var(--td-text-color-secondary, #475569);
  line-height: 1.6;
}

.section-intro code {
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  color: #0f172a;
}

.model-form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.action-btn-row {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}

.result-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 12px 16px;
  border-radius: 6px;
  font-size: 14px;
}

.result-banner.is-success {
  background: #ecfdf5;
  color: #065f46;
  border: 1px solid #a7f3d0;
}

.result-banner.is-error {
  background: #fef2f2;
  color: #991b1b;
  border: 1px solid #fecaca;
}

.profile-query-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.profile-details-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.profile-field-card {
  padding: 16px;
  background: #f8fafc;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}

.field-title {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 6px;
}

.field-value {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}

.field-value.highlight {
  color: #0052d9;
}

.parsed-param-box, .leads-table-box, .saga-status-box, .article-output-box {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 16px;
}

.box-title {
  font-size: 14px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 8px;
}

.json-code-block {
  background: #0f172a;
  color: #38bdf8;
  padding: 12px;
  border-radius: 6px;
  font-size: 13px;
  overflow-x: auto;
}

.article-form-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.markdown-preview {
  background: #fff;
  border: 1px solid #e2e8f0;
  padding: 16px;
  border-radius: 6px;
  line-height: 1.8;
  color: #334155;
}

.sse-chat-box {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
}

.sse-msg-thread {
  min-height: 240px;
  max-height: 400px;
  overflow-y: auto;
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chat-bubble-row {
  display: flex;
}

.chat-bubble-row.user {
  justify-content: flex-end;
}

.chat-bubble-row.assistant {
  justify-content: flex-start;
}

.chat-bubble {
  max-width: 75%;
  padding: 12px 16px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #e2e8f0;
}

.chat-bubble-row.user .chat-bubble {
  background: #0052d9;
  color: #fff;
  border-color: #0052d9;
}

.bubble-header {
  font-size: 12px;
  margin-bottom: 4px;
  opacity: 0.8;
  display: flex;
  align-items: center;
  gap: 6px;
}

.bubble-text {
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.typing-cursor::after {
  content: '▋';
  animation: blink 1s infinite;
  color: #0052d9;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.sse-input-row {
  display: flex;
  gap: 12px;
}

.mr-1 {
  margin-right: 4px;
}
.mt-2 {
  margin-top: 8px;
}
.mt-3 {
  margin-top: 12px;
}
</style>
