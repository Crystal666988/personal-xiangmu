import request from '@/utils/request'

export function routingModelConfig(modelConfig) {
  return {
    tenantId: modelConfig?.tenantId ?? null,
    provider: modelConfig?.provider?.trim() || '',
    apiKey: modelConfig?.apiKey?.trim() || '',
    baseUrl: modelConfig?.baseUrl?.trim() || '',
    modelName: modelConfig?.modelName?.trim() || ''
  }
}

/** Main chat entry: the server owns planning, clarification state and tool execution. */
export function prepareRoutedSessionContextApi(sessionId, currentMessageId, signal, tenantId, modelConfig, routingAction = 'continue') {
  return request({
    url: `/agent/sessions/${sessionId}/context`,
    method: 'post',
    data: { tenantId, currentMessageId, modelConfig: routingModelConfig(modelConfig), routingAction },
    signal,
    timeout: 90000
  })
}

export function cancelPendingRoutingApi(sessionId, tenantId) {
  return request({
    url: `/agent/sessions/${sessionId}/routing/cancel`,
    method: 'post',
    data: { tenantId },
    timeout: 15000
  })
}

/** Preview with the same model configuration used by chat; never dispatches tools. */
export function previewRoutingApi(query, modelConfig, signal) {
  return request({
    url: '/agent/routing/preview',
    method: 'post',
    data: {
      query,
      modelConfig: routingModelConfig(modelConfig)
    },
    signal,
    timeout: 20000
  })
}
