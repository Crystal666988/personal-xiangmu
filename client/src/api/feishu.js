import request from '@/utils/request'

// 飞书云文档 → RAG 知识库同步（后端真实走 AST 切片管道；文档内容当前为演示样例）
export function feishuDocsSyncApi(documentToken, documentId = 1001) {
  return request({
    url: '/api/trade/feishu/docs/sync',
    baseURL: '',
    method: 'post',
    params: { documentToken, documentId },
    timeout: 30000
  })
}

// 飞书多维表格 Bitable Webhook 链路模拟（异步背调与回写任务触发）
export function feishuBitableWebhookApi(payload) {
  return request({
    url: '/api/trade/feishu/bitable/webhook',
    baseURL: '',
    method: 'post',
    params: { tenantId: 1001 },
    data: payload,
    timeout: 30000
  })
}
