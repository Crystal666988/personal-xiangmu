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
