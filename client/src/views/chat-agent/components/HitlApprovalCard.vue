<template>
  <section class="approval-card" :aria-busy="busy">
    <div class="approval-heading">
      <strong>{{ waiting ? '高风险操作 · 等待人工审批' : '任务执行存档' }}</strong>
      <el-tag :type="waiting ? 'warning' : execution.status === 'COMPLETED' ? 'success' : 'info'">{{ execution.status }}</el-tag>
    </div>
    <p>{{ execution.riskReason || execution.message }}</p>
    <p class="meta">已完成 {{ execution.completedStepIds?.length || 0 }} / {{ execution.totalSteps }} 步
      <span v-if="execution.toolId"> · 当前工具：{{ execution.toolId }}</span></p>
    <div v-if="execution.timings" class="timings">
      <span>DAG 历时 {{ formatTime(execution.timings.executionWallMs) }}</span>
      <span>工具 {{ formatTime(execution.timings.forwardToolMs) }}</span>
      <span>纠参模型 {{ formatTime(execution.timings.reflectionModelMs) }}</span>
      <span>补偿工具 {{ formatTime(execution.timings.compensationToolMs) }}</span>
      <span>人工等待 {{ formatTime(execution.timings.approvalWaitMs) }}</span>
      <small>统计从执行存档创建开始；不含前置规划与最终回答生成。</small>
    </div>
    <div v-if="execution.compensations?.some(step => step.attempts > 0 || step.errorCode)" class="compensation-list">
      <strong>逆向补偿进度</strong>
      <p v-for="step in [...execution.compensations].reverse()" :key="step.stepId" class="meta">
        {{ step.toolId }}：{{ step.status }} · {{ step.attempts }} 次
        <span v-if="step.errorCode">（{{ step.errorCode }}）</span>
      </p>
    </div>
    <pre v-if="waiting">{{ JSON.stringify(execution.parameters, null, 2) }}</pre>
    <el-alert v-if="unavailable" title="执行状态暂不可确认，请刷新后再操作" type="error" :closable="false" />
    <el-alert v-if="expired" title="此存档已过期，请刷新执行状态" type="warning" :closable="false" />
    <template v-if="waiting">
      <el-checkbox v-model="editing" :disabled="busy || unavailable || expired">修改参数后批准</el-checkbox>
      <el-input v-if="editing" v-model="editedJson" type="textarea" :rows="6" :disabled="busy" aria-label="审批参数 JSON" />
      <div class="actions">
        <el-button type="primary" :loading="busy" :disabled="unavailable || expired" @click="approve">{{ editing ? '提交修改并批准' : '批准执行' }}</el-button>
        <el-button type="danger" plain :disabled="busy || unavailable || expired" @click="$emit('decision', 'REJECT')">驳回</el-button>
      </div>
    </template>
    <div class="actions">
      <el-button v-if="resumable" :loading="busy" :disabled="unavailable || expired" @click="$emit('resume')">{{ compensating ? '恢复逆向补偿' : '从存档恢复执行' }}</el-button>
      <el-button v-if="execution.status === 'FAILED'" type="danger" plain :disabled="busy || unavailable || expired" @click="$emit('cancel')">终止并补偿</el-button>
      <el-button :disabled="busy" @click="$emit('refresh')">刷新执行状态</el-button>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
const props = defineProps({ execution: { type: Object, required: true }, busy: Boolean, unavailable: Boolean })
const emit = defineEmits(['decision', 'resume', 'refresh', 'cancel'])
const editing = ref(false)
const editedJson = ref('')
const waiting = computed(() => props.execution.status === 'WAITING_APPROVAL')
const expired = computed(() => props.execution.expiresAt <= Date.now())
const compensating = computed(() => ['COMPENSATION_REQUIRED', 'COMPENSATING', 'COMPENSATION_FAILED'].includes(props.execution.status))
const resumable = computed(() => ['READY', 'RUNNING', 'APPROVED', 'FAILED'].includes(props.execution.status)
  || (compensating.value && !props.execution.compensations?.some(step => step.errorCode === 'COMPENSATION_UNSUPPORTED')))
const formatTime = value => value >= 1000 ? `${(value / 1000).toFixed(2)} s` : `${value || 0} ms`
watch(() => props.execution.approvalId, () => {
  editing.value = false
  editedJson.value = JSON.stringify(props.execution.parameters || {}, null, 2)
}, { immediate: true })
function approve() {
  if (!editing.value) return emit('decision', 'APPROVE')
  try {
    const parameters = JSON.parse(editedJson.value)
    if (!parameters || Array.isArray(parameters) || typeof parameters !== 'object' || !Object.keys(parameters).length) throw new Error()
    emit('decision', 'OVERRIDE', parameters)
  } catch { ElMessage.error('请输入非空 JSON 参数对象') }
}
</script>

<style scoped>
.approval-card { border: 1px solid #e6a23c; background: #fdf6ec; border-radius: 10px; padding: 18px; margin: 12px 0; }
.approval-heading, .actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.approval-heading { justify-content: space-between; }
.actions { margin-top: 12px; }
.meta { color: #606266; font-size: 13px; }
.timings { display: flex; gap: 12px; flex-wrap: wrap; font-size: 13px; margin: 12px 0; }
.timings small { flex-basis: 100%; color: #606266; }
.compensation-list { border-left: 3px solid #e6a23c; padding-left: 12px; margin: 12px 0; }
pre { padding: 12px; background: #fff; border-radius: 6px; max-height: 280px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
