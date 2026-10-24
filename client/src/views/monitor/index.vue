<template>
  <div class="monitor-container">
    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text">
            <el-icon><DataLine /></el-icon> Agent 执行监控
          </span>
          <div class="title-actions">
            <el-select
              v-model="sessionFilter"
              placeholder="全部会话"
              clearable
              style="width: 240px"
              size="default"
            >
              <el-option
                v-for="s in sessionOptions"
                :key="s.id"
                :label="s.title"
                :value="s.id"
              />
            </el-select>
            <el-button type="primary" :loading="loading" @click="fetchEntries">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        :data="filteredRows"
        style="width: 100%"
        size="default"
        v-loading="loading"
        :row-class-name="rowClassName"
      >
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
        <el-table-column prop="time" label="时间" width="110" />
        <el-table-column prop="sessionTitle" label="会话" min-width="140" show-overflow-tooltip />
        <el-table-column prop="query" label="用户提问" min-width="240" show-overflow-tooltip />
        <el-table-column label="路由状态" width="150">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.routingStatus === 'EXECUTED' ? 'success' : row.routingStatus === 'GENERAL' ? 'info' : 'warning'"
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
        <template #empty>
          <el-empty description="还没有执行记录，去对话工作台发一轮消息试试" />
        </template>
      </el-table>

      <div class="page-tip">
        数据来自每轮对话写入的路由元数据（agent_message.routing）：包含意图路由分析与每个工具的调用状态与耗时；
        API Key 等敏感信息不会出现在监控数据里。默认展示最近 50 轮。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { DataLine, Refresh } from '@element-plus/icons-vue'
import { getMonitorEntriesApi } from '@/api/agent'

const rows = ref([])
const loading = ref(false)
const sessionFilter = ref('')

const sessionOptions = computed(() => {
  const seen = new Map()
  rows.value.forEach(r => {
    if (!seen.has(r.sessionId)) seen.set(r.sessionId, { id: r.sessionId, title: r.sessionTitle })
  })
  return [...seen.values()]
})

const filteredRows = computed(() => {
  if (!sessionFilter.value) return rows.value
  return rows.value.filter(r => r.sessionId === sessionFilter.value)
})

const fetchEntries = async () => {
  loading.value = true
  try {
    const res = await getMonitorEntriesApi('1001', 50)
    rows.value = res && res.data ? res.data : []
  } catch (e) {
    console.warn('监控数据加载失败', e)
  } finally {
    loading.value = false
  }
}

const rowClassName = ({ row }) => (row.toolsCalledCount > 0 ? 'row-with-tools' : '')

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
    }

    .title-actions {
      display: flex;
      align-items: center;
      gap: 12px;
    }
  }
}

.expand-body {
  padding: 6px 24px 12px;
  display: flex;
  gap: 40px;

  .expand-block {
    min-width: 320px;
  }

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

.page-tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.font-mono {
  font-family: Consolas, Monaco, "Courier New", monospace;
}
</style>
