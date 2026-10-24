<template>
  <div class="iot-container">
    <el-card shadow="never" class="box-card">
      <div class="chain-row font-mono">
        智能设备 (温氏养殖环控/料线) ──MQTT──▶ EMQX Broker ──▶ 入库 MySQL ──▶ 阈值规则引擎
        ──▶ 异常事件 ──▶ 飞书预警卡片 (人工确认)　｜　每日 23:00 ──▶ 总结推送飞书多维表格
      </div>
    </el-card>

    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text"><el-icon><Cpu /></el-icon> 设备数据上报 (MQTT 演示 / HTTP 直入口径)</span>
        </div>
      </template>
      <el-form label-width="120px" size="default" class="ingest-form">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="栋舍"><el-input v-model="form.farmId" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="设备 ID"><el-input v-model="form.deviceId" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="采集时间"><el-input v-model="form.collectedAt" placeholder="留空 = 当前时间" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6"><el-form-item label="温度 ℃"><el-input-number v-model="form.temperature" :precision="1" :step="0.5" style="width:100%" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="湿度 %"><el-input-number v-model="form.humidity" :precision="0" :step="1" style="width:100%" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="氨气 ppm"><el-input-number v-model="form.nh3" :precision="0" :step="1" style="width:100%" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="CO₂ ppm"><el-input-number v-model="form.co2" :precision="0" :step="50" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-form-item>
          <el-button type="primary" :loading="ingesting" @click="ingest">
            <el-icon><Promotion /></el-icon> 上报这组数据
          </el-button>
          <el-button @click="fillAbnormal">填入一组超标数据 (演示预警)</el-button>
        </el-form-item>
        <el-form-item v-if="ingestResult">
          <el-alert type="success" :closable="false" show-icon :title="ingestResult" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text"><el-icon><WarningFilled /></el-icon> 最近异常事件 (触发飞书人工预警)</span>
          <el-button size="small" :loading="loading" @click="loadAnomalies"><el-icon><Refresh /></el-icon> 刷新</el-button>
        </div>
      </template>
      <el-table :data="anomalies" size="default" v-loading="loading">
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="farmId" label="栋舍" width="120" />
        <el-table-column prop="deviceId" label="设备" width="130" />
        <el-table-column label="指标" width="110">
          <template #default="{ row }">{{ metricLabel(row.metric) }}</template>
        </el-table-column>
        <el-table-column prop="metricValue" label="当前值" width="90" />
        <el-table-column prop="message" label="描述" min-width="260" show-overflow-tooltip />
        <el-table-column label="级别" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.severity === 'CRITICAL' ? 'danger' : 'warning'" effect="dark">
              {{ row.severity === 'CRITICAL' ? '紧急' : '观察' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="飞书预警" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.feishuState === 'SENT' ? 'success' : 'info'" effect="plain">
              {{ row.feishuState === 'SENT' ? '已推送' : row.feishuState === 'SKIPPED_NO_WEBHOOK' ? '未配置Webhook' : row.feishuState }}
            </el-tag>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无异常事件" :image-size="55" /></template>
      </el-table>
    </el-card>

    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text"><el-icon><Calendar /></el-icon> 每日设备总结 → 飞书多维表格</span>
          <el-tag size="small" type="info" effect="plain">定时任务 23:00 自动执行</el-tag>
        </div>
      </template>
      <el-button type="primary" :loading="summarizing" @click="runSummary">
        <el-icon><DataLine /></el-icon> 立即聚合今日并推送
      </el-button>
      <div v-if="summaryText" class="summary-box font-mono">{{ summaryText }}</div>
    </el-card>

    <div class="page-tip">
      链路说明：设备经 MQTT 上报（iot.mqtt.enabled=true 时连接 EMQX，默认关闭时用本页 HTTP 入口模拟同一链路）；
      入库后阈值规则引擎（温度 18~32℃ / 湿度 40~85% / 氨气 ≤20ppm / 数据中断 ≥10 分钟）生成异常事件并合并推送飞书卡片；
      数据点同时可经 Kafka 转发（iot.kafka.enabled=true 时）给离线分析。飞书侧需要：群机器人 Webhook（预警）、企业自建应用凭证（日报写 Bitable）。
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Calendar, Cpu, DataLine, Promotion, Refresh, WarningFilled } from '@element-plus/icons-vue'
import request from '@/utils/request'

const form = reactive({ farmId: 'farm_wens_001', deviceId: 'dev_env_01', collectedAt: '', temperature: 25, humidity: 60, nh3: 10, co2: 800 })
const ingesting = ref(false)
const ingestResult = ref('')
const anomalies = ref([])
const loading = ref(false)
const summarizing = ref(false)
const summaryText = ref('')

const metricLabel = (m) => ({ temperature: '温度', humidity: '湿度', nh3: '氨气', co2: 'CO₂', offline: '数据上报' }[m] || m)

const ingest = async () => {
  ingesting.value = true
  ingestResult.value = ''
  try {
    const metrics = [
      { metric: 'temperature', value: form.temperature, unit: '℃' },
      { metric: 'humidity', value: form.humidity, unit: '%' },
      { metric: 'nh3', value: form.nh3, unit: 'ppm' },
      { metric: 'co2', value: form.co2, unit: 'ppm' }
    ]
    const res = await request({
      url: '/api/v1/iot/ingest', baseURL: '', method: 'post',
      data: { tenantId: '1001', farmId: form.farmId, deviceId: form.deviceId, collectedAt: form.collectedAt || null, metrics }
    })
    const r = res.data || {}
    const alerts = (r.anomalies || []).map(a => a.message).join('；') || '全部正常'
    ingestResult.value = `入库 ${r.pointsSaved} 点；异常 ${r.anomalies.length} 条：${alerts}`
    ElMessage.success('上报成功')
    await loadAnomalies()
  } catch (err) {
    ElMessage.error('上报失败: ' + err.message)
  } finally {
    ingesting.value = false
  }
}

const fillAbnormal = () => {
  form.temperature = 35.5
  form.humidity = 88
  form.nh3 = 25
}

const loadAnomalies = async () => {
  loading.value = true
  try {
    const res = await request({ url: '/api/v1/iot/anomalies', baseURL: '', method: 'get', params: { tenantId: '1001', limit: 30 } })
    anomalies.value = res.data || []
  } finally {
    loading.value = false
  }
}

const runSummary = async () => {
  summarizing.value = true
  summaryText.value = ''
  try {
    const res = await request({ url: '/api/v1/iot/summary/now', baseURL: '', method: 'post', params: { tenantId: '1001' } })
    summaryText.value = res.data || '完成'
    ElMessage.success('今日总结已生成')
    await loadAnomalies()
  } catch (err) {
    ElMessage.error('总结失败: ' + err.message)
  } finally {
    summarizing.value = false
  }
}

onMounted(loadAnomalies)
</script>

<style scoped lang="scss">
.iot-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.box-card {
  border-radius: 6px;
  border: 1px solid #e4e7ed;

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
  }
}

.chain-row {
  font-size: 12.5px;
  color: #606266;
  line-height: 1.8;
  word-break: break-all;
}

.ingest-form { max-width: 1000px; }

.summary-box {
  margin-top: 12px;
  background: #282c34;
  color: #abb2bf;
  border-radius: 6px;
  padding: 12px 14px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
}

.page-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  padding: 0 4px;
}

.font-mono {
  font-family: Consolas, Monaco, "Courier New", monospace;
}
</style>
