import request from '@/utils/request'
import { routingModelConfig } from './routing'

export const fetchActiveExecutionApi = (sessionId, tenantId) => request({
  url: `/agent/sessions/${encodeURIComponent(sessionId)}/execution`, params: { tenantId }
})
export const fetchCheckpointApi = (checkpointId, sessionId, tenantId) => request({
  url: `/agent/checkpoints/${encodeURIComponent(checkpointId)}`, params: { sessionId, tenantId }
})
export const decideApprovalApi = (execution, tenantId, action, overrideParams, modelConfig) => request({
  url: `/agent/hitl/${encodeURIComponent(execution.approvalId)}/decision`, method: 'post',
  data: { tenantId, sessionId: execution.sessionId, taskId: execution.taskId, action, overrideParams,
    modelConfig: modelConfig ? routingModelConfig(modelConfig) : null }, timeout: 120000
})
export const resumeCheckpointApi = (execution, tenantId, modelConfig) => request({
  url: `/agent/checkpoints/${encodeURIComponent(execution.checkpointId)}/resume`, method: 'post',
  data: { tenantId, sessionId: execution.sessionId, modelConfig: modelConfig ? routingModelConfig(modelConfig) : null }, timeout: 120000
})
export const cancelCheckpointApi = (execution, tenantId) => request({
  url: `/agent/checkpoints/${encodeURIComponent(execution.checkpointId)}/cancel`, method: 'post',
  data: { tenantId, sessionId: execution.sessionId }, timeout: 120000
})
