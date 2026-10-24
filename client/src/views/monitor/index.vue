<template>
  <div class="monitor-container">
    <el-row :gutter="16">
      <!-- 左：会话空间分组 -->
      <el-col :xs="24" :sm="8" :md="7" :lg="6">
        <el-card shadow="never" class="box-card session-card">
          <template #header>
            <div class="card-title-bar">
              <span class="title-text">
                <el-icon><ChatDotRound /></el-icon> 会话空间
              </span>
              <el-button size="small" :loading="loading" @click="fetchEntries">
                <el-icon><Refresh /></el-icon> 刷新
              </el-button>
            </div>
          </template>
          <div class="session-scroll-list">
            <div
              v-for="group in sessionGroups"
              :key="group.sessionId"
              class="session-group-item"
              :class="{ active: selectedSessionId === group.sessionId }"
              @click="selectedSessionId = group.sessionId"
            >
              <div class="session-group-title text-truncate">{{ group.sessionTitle }}</div>
              <div class="session-group-meta">
                {{ group.rounds.length }} 轮 ｜ 最近 {{ group.lastTime }}
              </div>
            </div>
            <el-empty v-if="sessionGroups.length === 0 && !loading" description="暂无执行记录" :image-size="55" />
          </div>
        </el-card>
      </el-col>

      <!-- 右：选中会话的每轮执行明细 -->
      <el-col :xs="24" :sm="16" :md="17" :lg="18">
        <el-card shadow="never" class="box-card">
          <template #header>
            <div class="card-title-bar">
              <span class="title-text">
                <el-icon><DataLine /></el-icon> 每轮执行明细
                <span class="sub-count" v-if="currentGroup">（{{ currentGroup.sessionTitle }} · {{ currentGroup.rounds.length }} 轮）</span>
              </span>
            </div>
          </template>

          <el-table :data="currentRounds" style="width: 100%" size="default" v-loading="loading">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expand-body">
                  <div class="expand-block">
                    <div class="expand-block-title">🧭 意图路由分析</div>
                    <div class="expand-line"><span class="k">领域:</span> {{ row.domainName || row.domain || 'GENERAL' }}</div>
                    <div class="expand-line"><span class="k">路由依据:</span> {{ row.reason || '--' }}</div>
                    <div class="expand-line"><span class="k">工具剪枝:</span> 激活 {{ row.activeToolsCount }} 个 ｜ 剪枝 {{ row.prunedToolsCount }} 个</div>
                  </div>
                  <div class="expand-block">
                    <div class="expand-block-title">🛠️ 工具调用统计 (合计 {{ row.toolsCalledCount }} 次 / {{ row.totalLatencyMs }} ms)</div>
                    <template v-if="row.traces && row.traces.length">
                      <div v-for="(t, ti) in row.traces" :key="ti" class="tool-line">
                        <span class="tool-status" :class="t.status === 'SUCCESS' ? 'ok' : 'bad'"></span>
                        <span class="tool-name">{{ t.toolName || t.toolId }}</span>
                        <span class="font-mono tool-id">({{ t.toolId }})</span>
                        <el-tag size="small" :type="t.status === 'SUCCESS' ? 'success' : 'danger'" effect="plain">
                          {{ t.status === 'SUCCESS' ? '成功' : '失败' }}
                        </el-tag>
                        <span class="tool-latency font-mono">{{ t.latencyMs }} ms</span>
                      </div>
                    </template>
                    <div v-else class="expand-line">本轮未调用业务工具 (剪枝 100%)</div>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="time" label="时间" width="105" />
            <el-table-column prop="query" label="用户提问" min-width="220" show-overflow-tooltip />
            <el-table-column label="路由状态" width="140">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.routingStatus === 'EXECUTED' ? 'success' : row.routingStatus === 'GENERAL' ? 'info' : row.routingStatus ? 'warning' : 'info'"
                  effect="plain"
                >
                  {{ row.routingStatus || '--' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="意图领域" width="120">
              <template #default="{ row }">{{ row.domainName || row.domain || 'GENERAL' }}</template>
            </el-table-column>
            <el-table-column prop="toolsCalledCount" label="工具数" width="80" />
            <el-table-column label="总耗时" width="110">
              <template #default="{ row }">
                <span class="font-mono">{{ row.totalLatencyMs }} ms</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="openDetail(row)">详情</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="该会话暂无执行记录" />
            </template>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 执行详情抽屉：任务 JSON / 执行步骤 / 工具响应 / 耗时 -->
    <el-drawer v-model="detailVisible" title="轮次执行详情" size="58%">
      <div v-if="detailRow" class="detail-body">
        <div class="detail-section">
          <div class="detail-title">🧭 意图路由分析</div>
          <div class="kv"><span class="k">路由状态:</span> {{ detailRow.routingStatus || '--' }}</div>
          <div class="kv"><span class="k">领域:</span> {{ detailRow.domainName || detailRow.domain || 'GENERAL' }}</div>
          <div class="kv"><span class="k">路由依据:</span> {{ detailRow.reason || '--' }}</div>
          <div class="kv"><span class="k">工具剪枝:</span> 激活 {{ detailRow.activeToolsCount }} 个 ｜ 剪枝 {{ detailRow.prunedToolsCount }} 个</div>
        </div>

        <div class="detail-section">
          <div class="detail-title">📋 任务计划 JSON (LLM 规划输出)</div>
          <pre class="detail-code font-mono"><code>{{ prettyPlan }}</code></pre>
        </div>

        <div class="detail-section">
          <div class="detail-title">🛠️ 工具调用与耗时 ({{ detailRow.toolsCalledCount }} 次 / {{ detailRow.totalLatencyMs }} ms)</div>
          <template v-if="detailRow.traces && detailRow.traces.length">
            <div v-for="(t, ti) in detailRow.traces" :key="ti" class="tool-line">
              <span class="tool-status" :class="t.status === 'SUCCESS' ? 'ok' : 'bad'"></span>
              <span class="tool-name">{{ t.toolName || t.toolId }}</span>
              <span class="font-mono tool-id">({{ t.toolId }})</span>
              <el-tag size="small" :type="t.status === 'SUCCESS' ? 'success' : 'danger'" effect="plain">
                {{ t.status === 'SUCCESS' ? '成功' : '失败' }}
              </el-tag>
              <span class="tool-latency font-mono">{{ t.latencyMs }} ms</span>
            </div>
          </template>
          <div v-else class="kv">本轮未调用业务工具</div>
        </div>

        <div class="detail-section">
          <div class="detail-title">🔄 ReAct 执行步骤与工具响应</div>
          <template v-if="detailRow.reactSteps && detailRow.reactSteps.length">
            <div v-for="step in detailRow.reactSteps" :key="step.stepIndex" class="react-step-box">
              <div class="react-step-head">
                <span class="step-badge" :class="'badge-' + (step.type || '').toLowerCase()">{{ step.type }}</span>
                <span class="font-mono">#{{ step.stepIndex }}</span>
                <span v-if="step.toolName" class="font-mono">{{ step.toolName }} ({{ step.toolId }})</span>
                <span class="step-duration">{{ step.durationMs }} ms</span>
              </div>
              <div v-if="step.summary" class="react-step-summary">{{ step.summary }}</div>
              <pre v-if="hasContent(step.toolParams)" class="detail-code font-mono"><code>入参: {{ pretty(step.toolParams) }}</code></pre>
              <pre v-if="step.toolOutput" class="detail-code font-mono"><code>响应: {{ step.toolOutput }}</code></pre>
            </div>
          </template>
          <div v-else class="kv">旧数据未记录完整执行步骤明细（此轮之后的新对话才会记录）</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ChatDotRound, DataLine, Refresh } from '@element-plus/icons-vue'
import { getMonitorEntriesApi } from '@/api/agent'

const rows = ref([])
const loading = ref(false)
const selectedSessionId = ref('')
const detailVisible = ref(false)
const detailRow = ref(null)

// 会话空间分组：一个对话空间 = 它自己的所有轮次，不与其他会话混排
const sessionGroups = computed(() => {
  const groups = new Map()
  rows.value.forEach(r => {
    if (!groups.has(r.sessionId)) {
      groups.set(r.sessionId, {
        sessionId: r.sessionId,
        sessionTitle: r.sessionTitle || r.sessionId,
        rounds: [],
        lastTime: r.time
      })
    }
    groups.get(r.sessionId).rounds.push(r)
  })
  return [...groups.values()].sort((a, b) => (b.lastTime || '').localeCompare(a.lastTime || ''))
})

const currentGroup = computed(() =>
  sessionGroups.value.find(g => g.sessionId === selectedSessionId.value) || null)

const currentRounds = computed(() =>
  currentGroup.value ? currentGroup.value.rounds : [])

const fetchEntries = async () => {
  loading.value = true
  try {
    const res = await getMonitorEntriesApi('1001', 200)
    rows.value = res && res.data ? res.data : []
    if (!rows.value.some(r => r.sessionId === selectedSessionId.value)) {
      selectedSessionId.value = sessionGroups.value.length ? sessionGroups.value[0].sessionId : ''
    }
  } catch (e) {
    console.warn('监控数据加载失败', e)
  } finally {
    loading.value = false
  }
}

const openDetail = (row) => {
  detailRow.value = row
  detailVisible.value = true
}

const pretty = (value) => {
  try {
    return typeof value === 'string' ? JSON.stringify(JSON.parse(value), null, 2) : JSON.stringify(value, null, 2)
  } catch (e) {
    return String(value)
  }
}

const prettyPlan = computed(() => {
  if (!detailRow.value || !detailRow.value.planJson) return '--（该轮没有任务计划 JSON；GENERAL 闲聊轮或旧数据）'
  return pretty(detailRow.value.planJson)
})

const hasContent = (value) =>
  value != null && !(typeof value === 'object' && Object.keys(value).length === 0)

onMounted(fetchEntries)
</script>

<style scoped lang="scss">
.monitor-container {
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

      .sub-count {
        font-size: 12px;
        font-weight: 400;
        color: #909399;
      }
    }

    .title-actions {
      display: flex;
      align-items: center;
      gap: 12px;
    }
  }
}

.session-scroll-list {
  max-height: 560px;
  overflow-y: auto;

  .session-group-item {
    padding: 10px 12px;
    border-radius: 6px;
    cursor: pointer;
    border: 1px solid transparent;
    transition: all 0.2s;

    &:hover { background: #f5f7fa; }
    &.active {
      background: #ecf5ff;
      border-color: #409eff;
    }

    .session-group-title {
      font-size: 13.5px;
      font-weight: 600;
      color: #303133;
    }

    .session-group-meta {
      font-size: 12px;
      color: #909399;
      margin-top: 3px;
    }
  }
}

.expand-body {
  padding: 6px 24px 12px;
  display: flex;
  gap: 40px;

  .expand-block { min-width: 320px; }

  .expand-block-title {
    font-size: 13px;
    font-weight: 600;
    color: #303133;
    margin-bottom: 8px;
  }

  .expand-line {
    font-size: 12.5px;
    color: #606266;
    margin: 4px 0;
    .k { color: #909399; margin-right: 4px; }
  }

  .tool-line {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12.5px;
    color: #606266;
    margin: 5px 0;

    .tool-status {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      &.ok { background: #67c23a; }
      &.bad { background: #f56c6c; }
    }

    .tool-name { font-weight: 500; color: #303133; }
    .tool-id { color: #909399; font-size: 11.5px; }
    .tool-latency { margin-left: auto; color: #409eff; }
  }
}

.detail-body {
  .detail-section {
    margin-bottom: 22px;
    padding-bottom: 14px;
    border-bottom: 1px dashed #e4e7ed;

    &:last-child { border-bottom: none; }
  }

  .detail-title {
    font-size: 14px;
    font-weight: 600;
    color: #303133;
    margin-bottom: 10px;
  }

  .kv {
    font-size: 13px;
    color: #606266;
    margin: 5px 0;
    .k { color: #909399; margin-right: 4px; }
  }

  .detail-code {
    background: #282c34;
    color: #abb2bf;
    border-radius: 6px;
    padding: 12px 14px;
    font-size: 12px;
    overflow-x: auto;
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 320px;
    overflow-y: auto;
    margin: 6px 0;
  }

  .react-step-box {
    border: 1px solid #ebeef5;
    border-radius: 6px;
    padding: 10px 12px;
    margin-bottom: 10px;

    .react-step-head {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12.5px;
      color: #303133;

      .step-badge {
        padding: 1px 8px;
        border-radius: 4px;
        font-size: 11px;
        font-weight: 600;
        &.badge-thought { background: #ecf5ff; color: #409eff; }
        &.badge-action { background: #fdf6ec; color: #e6a23c; }
        &.badge-observation { background: #f0f9eb; color: #67c23a; }
        &.badge-final_answer { background: #f4f4f5; color: #909399; }
      }

      .step-duration { color: #909399; margin-left: auto; }
    }

    .react-step-summary {
      font-size: 12.5px;
      color: #606266;
      margin-top: 6px;
    }
  }

  .tool-line {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12.5px;
    color: #606266;
    margin: 5px 0;

    .tool-status {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      &.ok { background: #67c23a; }
      &.bad { background: #f56c6c; }
    }

    .tool-name { font-weight: 500; color: #303133; }
    .tool-id { color: #909399; font-size: 11.5px; }
    .tool-latency { margin-left: auto; color: #409eff; }
  }
}

.page-tip,
.text-truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.font-mono {
  font-family: Consolas, Monaco, "Courier New", monospace;
}
</style>
