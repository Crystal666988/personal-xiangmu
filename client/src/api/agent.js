import request from '@/utils/request'

// 工具目录与路由规则由后端 IntentRouter 维护，前端只展示返回结果�?

/**
 * 真实上传文件并调用后端三级多模态降级解析与阿里�?OSS 归档接口
 * @param {File} file 真实上传的文件对�?(PDF/图片/文档)
 */
export async function uploadAndExtractDocumentApi(file) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('doChunk', 'true')

  try {
    const res = await request({
      url: '/vision/extract',
      method: 'post',
      data: formData,
      headers: {
        'Content-Type': 'multipart/form-data'
      },
      timeout: 30000
    })

    if (res && res.data) {
      return res.data
    }
  } catch (err) {
    console.error('后端上传解析接口异常:', err)
    throw new Error(`后端文档解析失败: ${err.message || '网络连接超时或服务未就绪'}`)
  }
}

// ======================== MySQL 会话与消息持久化接口 ========================

/**
 * �?MySQL 数据库获取会话列�?
 */
export function fetchSessionsApi(tenantId = '1001') {
  return request({
    url: '/agent/sessions',
    method: 'get',
    params: { tenantId }
  })
}

/**
 * �?MySQL 数据库创建或保存会话
 */
export function createSessionApi(data) {
  return request({
    url: '/agent/sessions',
    method: 'post',
    data
  })
}

/**
 * 更新 MySQL 数据库中的会话标�?
 */
export function updateSessionTitleApi(sessionId, title) {
  return request({
    url: `/agent/sessions/${sessionId}`,
    method: 'put',
    params: { title }
  })
}

/**
 * �?MySQL 数据库删除会话及其关联消�?
 */
export function deleteSessionApi(sessionId) {
  return request({
    url: `/agent/sessions/${sessionId}`,
    method: 'delete'
  })
}

/**
 * �?MySQL 数据库获取指定会话的全部历史消息
 */
export function fetchSessionMessagesApi(sessionId) {
  return request({
    url: `/agent/sessions/${sessionId}/messages`,
    method: 'get'
  })
}

/**
 * 将单条消息持久化落库�?MySQL 数据�?
 */
export function saveMessageApi(sessionId, data) {
  return request({
    url: `/agent/sessions/${sessionId}/messages`,
    method: 'post',
    data
  })
}

// 路由和历史窗口由后端统一决定，停止生成时可取消本次上下文请求�?
export function prepareSessionContextApi(sessionId, currentMessageId, signal, tenantId = '1001') {
  return request({
    url: `/agent/sessions/${sessionId}/context`,
    method: 'post',
    data: { tenantId, currentMessageId },
    signal,
    timeout: 30000 // 首次需要调用后端摘要模型，不能沿用普通接口的10秒上限�?
  })
}

/**
 * 清空 MySQL 数据库中指定会话的历史消�?
 */
export function clearSessionMessagesApi(sessionId) {
  return request({
    url: `/agent/sessions/${sessionId}/messages`,
    method: 'delete'
  })
}

/**
 * �?MySQL 数据库删除单条消�?
 */
export function deleteMessageApi(sessionId, messageId) {
  return request({
    url: `/agent/sessions/${sessionId}/messages/${messageId}`,
    method: 'delete'
  })
}

/**
 * �?MySQL 数据库批量删除消�?(用于删除一整轮对话)
 */
export function deleteBatchMessagesApi(sessionId, messageIds) {
  return request({
    url: `/agent/sessions/${sessionId}/messages/batch-delete`,
    method: 'post',
    data: messageIds
  })
}

/**
 * 运行 RAG 默认 200 题金标全自动基准测试
 */
export function runRagDefaultBenchmarkApi() {
  return request({
    url: '/vision/benchmark/run',
    method: 'get',
    timeout: 60000
  })
}

/**
 * 上传任意领域文档并运�?200 题动态逆向出题与全自动评测
 * @param {File} file
 */
export function uploadAndRunRagBenchmarkApi(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/vision/benchmark/upload-and-run',
    method: 'post',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    timeout: 240000
  })
}

// ======================== 工业级两阶段知识库与真实 AI 评测接口 ========================

/**
 * 1. 获取已入库知识库文档列表 (阶段一：离线入�?
 */
export function getKnowledgeDocumentsApi() {
  return request({
    url: '/kb/documents',
    method: 'get'
  })
}

/**
 * 2. 上传文档并执行真�?AST 结构化切片入�?
 * @param {File} file
 */
export function uploadKnowledgeDocumentApi(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/kb/documents/upload',
    method: 'post',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    timeout: 300000
  })
}

/**
 * 3. 获取指定文档的所有切片明�?(切片透视)
 * @param {string} docId
 */
export function getDocumentChunksApi(docId) {
  return request({
    url: `/kb/documents/${docId}/chunks`,
    method: 'get'
  })
}

/**
 * 4. 删除指定知识库文档及其所有切�?
 * @param {string} docId
 */
export function deleteKnowledgeDocumentApi(docId) {
  return request({
    url: `/kb/documents/${docId}`,
    method: 'delete'
  })
}

/**
 * 5. 仅执�?BM25 切片检�?
 * @param {{ question: string, docId?: string, topK?: number }} data
 */
export function retrieveKnowledgeChunksApi(data) {
  return request({
    url: '/kb/qa/retrieve',
    method: 'post',
    data
  })
}

/**
 * 6. 在线 RAG 问答 (阶段二：检索切�?+ 真实大模型生�?+ 来源溯源引用)
 * @param {{ question: string, docId?: string, topK?: number, apiKey?: string, baseUrl?: string, modelName?: string }} data
 */
export function onlineKnowledgeAskApi(data) {
  return request({
    url: '/kb/qa/ask',
    method: 'post',
    data,
    timeout: 60000
  })
}

/**
 * 7. 真实 AI 大模型阅读切片并出题评测 (阶段三：真实 AI 出题 + 真实倒排检�?+ 拒绝纯正�?
 * @param {{ docId?: string, count?: number, apiKey?: string, baseUrl?: string, modelName?: string }} data
 */
export function runAiSyntheticBenchmarkApi(data) {
  return request({
    url: '/kb/benchmark/ai-run',
    method: 'post',
    data,
    timeout: 240000
  })
}

/**
 * 8. 获取最新一�?AI 评测的持久化报告
 */
export function getLatestBenchmarkReportApi() {
  return request({
    url: '/kb/benchmark/latest',
    method: 'get'
  })
}

/**
 * 9. 分页查询历史评测报告列表
 * @param {{ pageNum?: number, pageSize?: number, docId?: string }} params
 */
export function getBenchmarkReportsPageApi(params) {
  return request({
    url: '/kb/benchmark/page',
    method: 'get',
    params
  })
}

/**
 * 10. 获取历史单次评测报告完整详情 (包含所有考题与命中明�?
 * @param {string} id
 */
export function getBenchmarkReportDetailApi(id) {
  return request({
    url: `/kb/benchmark/${id}`,
    method: 'get'
  })
}

/**
 * 11. 删除历史评测报告
 * @param {string} id
 */
export function deleteBenchmarkReportApi(id) {
  return request({
    url: `/kb/benchmark/${id}`,
    method: 'delete'
  })
}

/**
 * 12. 分页查询在线 RAG 问答历史记录
 * @param {{ pageNum?: number, pageSize?: number, docId?: string, keyword?: string }} params
 */
export function getQaRecordsPageApi(params) {
  return request({
    url: '/kb/qa/page',
    method: 'get',
    params
  })
}

/**
 * 13. 标记问答准确率与审计反馈
 * @param {string} id
 * @param {{ rating: 'ACCURATE' | 'INACCURATE' | 'PARTIAL', feedback?: string }} data
 */
export function rateQaRecordApi(id, data) {
  return request({
    url: `/kb/qa/${id}/rate`,
    method: 'post',
    data
  })
}

/**
 * 14. 删除单条在线问答记录
 * @param {string} id
 */
export function deleteQaRecordApi(id) {
  return request({
    url: `/kb/qa/${id}`,
    method: 'delete'
  })
}

/**
 * 15. 获取系统中已注册的可执行工具列表与入�?Schema
 */
export function getRegisteredToolsApi() {
  return request({
    url: '/agent/tools/registry',
    method: 'get'
  })
}

/**
 * 16. 手动调试/执行单个工具，获取结构化结果与耗时
 * @param {string} toolId
 * @param {Object} params
 */
export function executeToolApi(toolId, params) {
  return request({
    url: '/agent/tools/execute',
    method: 'post',
    data: { toolId, params }
  })
}

/**
 * 17. 自动路由并派发工具，聚合整轮对话遥测数据 (TurnTelemetry)
 * @param {string} query
 * @param {string} [attachmentName]
 */
export function autoDispatchToolsApi(query, attachmentName) {
  return request({
    url: '/agent/tools/auto-dispatch',
    method: 'post',
    data: { query, attachmentName }
  })
}

// ======================== 意图路由 200 题基准评�?API ========================

/**
 * 18. 一键执行意图路�?200 题基准自动化跑批
 */
export function runIntentBenchmarkApi() {
  return request({
    url: '/agent/intent-benchmark/run',
    method: 'post',
    timeout: 60000
  })
}

/**
 * 19. 获取最新一次意图评测持久化报告
 */
export function getLatestIntentBenchmarkApi() {
  return request({
    url: '/agent/intent-benchmark/latest',
    method: 'get'
  })
}

/**
 * 20. 分页查询历史意图基准评测记录
 */
export function getIntentBenchmarkPageApi(params) {
  return request({
    url: '/agent/intent-benchmark/page',
    method: 'get',
    params
  })
}

/**
 * 21. 获取单次意图评测完整详情报告 (包含 200 题各题判定与混淆矩阵)
 */
export function getIntentBenchmarkDetailApi(id) {
  return request({
    url: `/agent/intent-benchmark/${id}`,
    method: 'get'
  })
}

/**
 * 22. 删除历史意图评测报告
 */
export function deleteIntentBenchmarkApi(id) {
  return request({
    url: `/agent/intent-benchmark/${id}`,
    method: 'delete'
  })
}

// ======================== 智能�?Skill 技能中�?API ========================

/**
 * 23. 获取系统内全部已挂载 Skill 技能清�?(包含绑定原子工具、系统提示词与调用统�?
 */
export function getSkillsListApi() {
  return request({
    url: '/agent/skills/list',
    method: 'get'
  })
}

/**
 * 24. 切换指定 Skill 技能的激�?停用状�?
 */
export function toggleSkillApi(id) {
  return request({
    url: `/agent/skills/${id}/toggle`,
    method: 'post'
  })
}

/**
 * 25. 单技能手动触发调试运�?
 */
export function executeSkillApi(id, params) {
  return request({
    url: `/agent/skills/${id}/execute`,
    method: 'post',
    data: { params }
  })
}

