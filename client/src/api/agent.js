import request from '@/utils/request'

// 工具目录与路由规则由后端 IntentRouter 维护，前端只展示返回结果。

/**
 * 真实上传文件并调用后端三级多模态降级解析与阿里云 OSS 归档接口
 * @param {File} file 真实上传的文件对象 (PDF/图片/文档)
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
 * 从 MySQL 数据库获取会话列表
 */
export function fetchSessionsApi(tenantId = '1001') {
  return request({
    url: '/agent/sessions',
    method: 'get',
    params: { tenantId }
  })
}

/**
 * 向 MySQL 数据库创建或保存会话
 */
export function createSessionApi(data) {
  return request({
    url: '/agent/sessions',
    method: 'post',
    data
  })
}

/**
 * 更新 MySQL 数据库中的会话标题
 */
export function updateSessionTitleApi(sessionId, title) {
  return request({
    url: `/agent/sessions/${sessionId}`,
    method: 'put',
    params: { title }
  })
}

/**
 * 从 MySQL 数据库删除会话及其关联消息
 */
export function deleteSessionApi(sessionId) {
  return request({
    url: `/agent/sessions/${sessionId}`,
    method: 'delete'
  })
}

/**
 * 从 MySQL 数据库获取指定会话的全部历史消息
 */
export function fetchSessionMessagesApi(sessionId) {
  return request({
    url: `/agent/sessions/${sessionId}/messages`,
    method: 'get'
  })
}

/**
 * 将单条消息持久化落库到 MySQL 数据库
 */
export function saveMessageApi(sessionId, data) {
  return request({
    url: `/agent/sessions/${sessionId}/messages`,
    method: 'post',
    data
  })
}

// 路由和历史窗口由后端统一决定，停止生成时可取消本次上下文请求。
export function prepareSessionContextApi(sessionId, currentMessageId, signal, tenantId = '1001') {
  return request({
    url: `/agent/sessions/${sessionId}/context`,
    method: 'post',
    data: { tenantId, currentMessageId },
    signal,
    timeout: 30000 // 首次需要调用后端摘要模型，不能沿用普通接口的10秒上限。
  })
}

/**
 * 清空 MySQL 数据库中指定会话的历史消息
 */
export function clearSessionMessagesApi(sessionId) {
  return request({
    url: `/agent/sessions/${sessionId}/messages`,
    method: 'delete'
  })
}

/**
 * 从 MySQL 数据库删除单条消息
 */
export function deleteMessageApi(sessionId, messageId) {
  return request({
    url: `/agent/sessions/${sessionId}/messages/${messageId}`,
    method: 'delete'
  })
}

/**
 * 从 MySQL 数据库批量删除消息 (用于删除一整轮对话)
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
 * 上传任意领域文档并运行 200 题动态逆向出题与全自动评测
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
    timeout: 120000
  })
}

// ======================== 工业级两阶段知识库与真实 AI 评测接口 ========================

/**
 * 1. 获取已入库知识库文档列表 (阶段一：离线入库)
 */
export function getKnowledgeDocumentsApi() {
  return request({
    url: '/kb/documents',
    method: 'get'
  })
}

/**
 * 2. 上传文档并执行真实 AST 结构化切片入库
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
    timeout: 60000
  })
}

/**
 * 3. 获取指定文档的所有切片明细 (切片透视)
 * @param {string} docId
 */
export function getDocumentChunksApi(docId) {
  return request({
    url: `/kb/documents/${docId}/chunks`,
    method: 'get'
  })
}

/**
 * 4. 删除指定知识库文档及其所有切片
 * @param {string} docId
 */
export function deleteKnowledgeDocumentApi(docId) {
  return request({
    url: `/kb/documents/${docId}`,
    method: 'delete'
  })
}

/**
 * 5. 仅执行 BM25 切片检索
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
 * 6. 在线 RAG 问答 (阶段二：检索切片 + 真实大模型生成 + 来源溯源引用)
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
 * 7. 真实 AI 大模型阅读切片并出题评测 (阶段三：真实 AI 出题 + 真实倒排检验 + 拒绝纯正则)
 * @param {{ docId?: string, count?: number, apiKey?: string, baseUrl?: string, modelName?: string }} data
 */
export function runAiSyntheticBenchmarkApi(data) {
  return request({
    url: '/kb/benchmark/ai-run',
    method: 'post',
    data,
    timeout: 120000
  })
}


