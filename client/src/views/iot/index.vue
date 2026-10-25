<template>
  <div class="iot-container">
    <el-card shadow="never" class="box-card">
      <div class="chain-row font-mono">
        智能设备 (温氏养殖环控/料线) ──MQTT──▶ EMQX Broker ──▶ 入库 MySQL ──▶ 阈值规则引擎
        ──▶ 异常事件 ──▶ 飞书预警卡片 (人工确认)　｜　每日 23:00 ──▶ 总结推送飞书多维表格
      </div>
    </el-card>

    <!-- 产品型号（设备型号/物模型）管理 -->
    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text"><el-icon><Box /></el-icon> 设备型号管理 (产品 → 设备 1:N)</span>
          <el-button type="primary" size="default" @click="openProductDialog()"><el-icon><Plus /></el-icon> 新增型号</el-button>
        </div>
      </template>
      <el-table :data="products" size="default" v-loading="productsLoading">
        <el-table-column prop="id" label="产品 ID" width="140" />
        <el-table-column prop="name" label="产品名称" width="150" />
        <el-table-column prop="model" label="设备型号" width="130" />
        <el-table-column prop="protocol" label="协议" width="75" />
        <el-table-column prop="vendor" label="厂商" width="140" />
        <el-table-column label="物模型指标" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ metricsSummary(row.metricsJson) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="125" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openProductDialog(row)">编辑</el-button>
            <el-button size="small" type="danger" link @click="deleteProduct(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无设备型号" :image-size="55" /></template>
      </el-table>
    </el-card>

    <!-- 设备台账（型号/地点/指令下发） -->
    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text"><el-icon><Cpu /></el-icon> 设备台账 (注册后才可上报数据)</span>
          <el-button type="primary" size="default" @click="openDeviceDialog()"><el-icon><Plus /></el-icon> 录入设备</el-button>
        </div>
      </template>
      <el-table :data="devices" size="default" v-loading="devicesLoading">
        <el-table-column prop="deviceId" label="设备 ID" width="125" />
        <el-table-column prop="name" label="设备名称" width="120" />
        <el-table-column prop="farmId" label="栋舍" width="125" />
        <el-table-column label="设备型号" width="120">
          <template #default="{ row }">{{ productName(row.productId) }}</template>
        </el-table-column>
        <el-table-column prop="location" label="安装地点" min-width="190" show-overflow-tooltip />
        <el-table-column label="温度阈值" width="105">
          <template #default="{ row }">{{ row.tempLow ?? 18 }}~{{ row.tempHigh ?? 32 }}℃</template>
        </el-table-column>
        <el-table-column prop="lastSeen" label="最近上报" width="160" />
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" link @click="openCmdDialog(row)">发指令</el-button>
            <el-button size="small" type="primary" link @click="openDeviceDialog(row)">编辑</el-button>
            <el-button size="small" type="danger" link @click="deleteDevice(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无设备，请先录入" :image-size="55" /></template>
      </el-table>
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

    <!-- 产品型号 新增/编辑 -->
    <el-dialog v-model="productDialog.visible" :title="productDialog.mode === 'add' ? '新增设备型号' : '编辑设备型号'" width="620px" destroy-on-close>
      <el-form :model="productDialog.form" label-width="110px">
        <el-form-item label="产品 ID" required>
          <el-input v-model="productDialog.form.id" :disabled="productDialog.mode === 'edit'" placeholder="prod_env_ctl" />
        </el-form-item>
        <el-form-item label="产品名称" required><el-input v-model="productDialog.form.name" placeholder="养殖环控器" /></el-form-item>
        <el-form-item label="设备型号" required><el-input v-model="productDialog.form.model" placeholder="WS-ENV-3000" /></el-form-item>
        <el-form-item label="接入协议"><el-input v-model="productDialog.form.protocol" placeholder="MQTT" /></el-form-item>
        <el-form-item label="厂商"><el-input v-model="productDialog.form.vendor" placeholder="温氏智能设备部" /></el-form-item>
        <el-form-item label="物模型 JSON">
          <el-input v-model="productDialog.form.metricsJson" type="textarea" :rows="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="productDialog.visible = false">取 消</el-button>
        <el-button type="primary" @click="saveProduct">保 存</el-button>
      </template>
    </el-dialog>

    <!-- 设备 新增/编辑 -->
    <el-dialog v-model="deviceDialog.visible" :title="deviceDialog.mode === 'add' ? '录入设备' : '编辑设备'" width="620px" destroy-on-close>
      <el-form :model="deviceDialog.form" label-width="110px">
        <el-form-item label="设备 ID" required>
          <el-input v-model="deviceDialog.form.deviceId" :disabled="deviceDialog.mode === 'edit'" placeholder="dev_env_01" />
        </el-form-item>
        <el-form-item label="设备名称" required><el-input v-model="deviceDialog.form.name" placeholder="1号栋环控器" /></el-form-item>
        <el-form-item label="栋舍" required><el-input v-model="deviceDialog.form.farmId" placeholder="farm_wens_001" /></el-form-item>
        <el-form-item label="设备型号">
          <el-select v-model="deviceDialog.form.productId" placeholder="选择产品型号" clearable style="width: 100%">
            <el-option v-for="p in products" :key="p.id" :label="p.name + ' (' + p.model + ')'" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="安装地点"><el-input v-model="deviceDialog.form.location" placeholder="广东云浮新兴县温氏总部基地 2号栋 A区" /></el-form-item>
        <el-form-item label="设备类型"><el-input v-model="deviceDialog.form.type" placeholder="ENV / FEED / WATER" /></el-form-item>
        <el-row>
          <el-col :span="12"><el-form-item label="温度下限"><el-input-number v-model="deviceDialog.form.tempLow" :precision="0" style="width:90%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="温度上限"><el-input-number v-model="deviceDialog.form.tempHigh" :precision="0" style="width:90%" /></el-form-item></el-col>
        </el-row>
        <el-row>
          <el-col :span="12"><el-form-item label="湿度下限"><el-input-number v-model="deviceDialog.form.humidLow" :precision="0" style="width:90%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="湿度上限"><el-input-number v-model="deviceDialog.form.humidHigh" :precision="0" style="width:90%" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="deviceDialog.visible = false">取 消</el-button>
        <el-button type="primary" @click="saveDevice">保 存</el-button>
      </template>
    </el-dialog>

    <!-- 指令下发 -->
    <el-dialog v-model="cmdDialog.visible" :title="'下发指令 → ' + cmdDialog.deviceId" width="560px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="快捷指令">
          <el-button size="small" @click="cmdDialog.form.commandJson = JSON.stringify({command:'open_window', percent:50})">开窗 50%</el-button>
          <el-button size="small" @click="cmdDialog.form.commandJson = JSON.stringify({command:'start_feed', duration_min:30, speed:'medium'})">喂料 30min</el-button>
          <el-button size="small" @click="cmdDialog.form.commandJson = JSON.stringify({command:'set_temp', target:26})">设定温度 26℃</el-button>
        </el-form-item>
        <el-form-item label="指令 JSON">
          <el-input v-model="cmdDialog.form.commandJson" type="textarea" :rows="4" />
        </el-form-item>
      </el-form>
      <div v-if="cmdDialog.resultText" class="cmd-result">{{ cmdDialog.resultText }}</div>
      <div v-if="cmdDialog.history.length" style="margin-top:8px">
        <div style="font-size:12px;color:#909399;margin-bottom:4px">最近下发：</div>
        <div v-for="c in cmdDialog.history" :key="c.id" class="cmd-history-row font-mono">
          {{ c.createTime }} | {{ c.state }} | {{ (c.commandJson || '').slice(0, 60) }}
        </div>
      </div>
      <template #footer>
        <el-button @click="cmdDialog.visible = false">关 闭</el-button>
        <el-button type="primary" @click="sendCommand">下发指令</el-button>
      </template>
    </el-dialog>

</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Box, Calendar, Cpu, DataLine, Plus, Promotion, Refresh, WarningFilled } from '@element-plus/icons-vue'
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

// ======================== 产品型号管理 ========================
const products = ref([])
const productsLoading = ref(false)
const productDialog = reactive({
  visible: false, mode: 'add',
  form: { id: '', name: '', model: '', protocol: 'MQTT', vendor: '', metricsJson: '' }
})

const loadProducts = async () => {
  productsLoading.value = true
  try {
    const res = await request({ url: '/api/v1/iot/products', baseURL: '', method: 'get', params: { tenantId: '1001' } })
    products.value = res.data || []
  } finally { productsLoading.value = false }
}

const metricsSummary = (jsonStr) => {
  try {
    const arr = JSON.parse(jsonStr || '[]')
    return arr.map(m => m.label + '(' + (m.low ?? '-') + '~' + (m.high ?? '-') + (m.unit || '') + ')').join('、') || '--'
  } catch { return jsonStr ? jsonStr.slice(0, 60) : '--' }
}

const openProductDialog = (row) => {
  productDialog.mode = row ? 'edit' : 'add'
  productDialog.form = row
    ? { id: row.id, name: row.name, model: row.model, protocol: row.protocol, vendor: row.vendor || '', metricsJson: row.metricsJson || '' }
    : { id: '', name: '', model: '', protocol: 'MQTT', vendor: '', metricsJson: '' }
  productDialog.visible = true
}

const saveProduct = async () => {
  try {
    if (productDialog.mode === 'add') {
      await request({ url: '/api/v1/iot/products', baseURL: '', method: 'post', params: { tenantId: '1001' }, data: productDialog.form })
    } else {
      await request({ url: '/api/v1/iot/products/' + productDialog.form.id, baseURL: '', method: 'put', params: { tenantId: '1001' }, data: productDialog.form })
    }
    ElMessage.success('型号已保存')
    productDialog.visible = false
    await loadProducts()
  } catch (err) { ElMessage.error('保存失败: ' + (err.message || '')) }
}

const deleteProduct = async (row) => {
  try { await ElMessageBox.confirm('确定删除型号「' + row.name + '」？', '删除确认', { type: 'warning' }) } catch { return }
  try {
    await request({ url: '/api/v1/iot/products/' + row.id, baseURL: '', method: 'delete', params: { tenantId: '1001' } })
    ElMessage.success('已删除')
    await loadProducts()
  } catch (err) { ElMessage.error(err.message || '删除失败') }
}

const productName = (pid) => {
  const p = products.value.find(x => x.id === pid)
  return p ? p.model : (pid || '--')
}

// ======================== 设备台账 ========================
const devices = ref([])
const devicesLoading = ref(false)
const deviceDialog = reactive({
  visible: false, mode: 'add',
  form: { deviceId: '', farmId: '', name: '', type: 'ENV', productId: '', location: '', tempLow: 18, tempHigh: 32, humidLow: 40, humidHigh: 85 }
})

const loadDevices = async () => {
  devicesLoading.value = true
  try {
    const res = await request({ url: '/api/v1/iot/devices', baseURL: '', method: 'get', params: { tenantId: '1001' } })
    devices.value = res.data || []
  } finally { devicesLoading.value = false }
}

const openDeviceDialog = (row) => {
  deviceDialog.mode = row ? 'edit' : 'add'
  deviceDialog.form = row
    ? { deviceId: row.deviceId, farmId: row.farmId, name: row.name, type: row.type, productId: row.productId || '', location: row.location || '',
        tempLow: row.tempLow ?? 18, tempHigh: row.tempHigh ?? 32, humidLow: row.humidLow ?? 40, humidHigh: row.humidHigh ?? 85 }
    : { deviceId: '', farmId: '', name: '', type: 'ENV', productId: '', location: '', tempLow: 18, tempHigh: 32, humidLow: 40, humidHigh: 85 }
  deviceDialog.visible = true
}

const saveDevice = async () => {
  try {
    if (deviceDialog.mode === 'add') {
      await request({ url: '/api/v1/iot/devices', baseURL: '', method: 'post', params: { tenantId: '1001' }, data: deviceDialog.form })
    } else {
      await request({ url: '/api/v1/iot/devices/' + deviceDialog.form.deviceId, baseURL: '', method: 'put', params: { tenantId: '1001' }, data: deviceDialog.form })
    }
    ElMessage.success('设备已保存')
    deviceDialog.visible = false
    await loadDevices()
  } catch (err) { ElMessage.error('保存失败: ' + (err.message || '')) }
}

const deleteDevice = async (row) => {
  try { await ElMessageBox.confirm('确定删除设备「' + row.name + '」？历史数据保留。', '删除确认', { type: 'warning' }) } catch { return }
  try {
    await request({ url: '/api/v1/iot/devices/' + row.deviceId, baseURL: '', method: 'delete', params: { tenantId: '1001' } })
    ElMessage.success('已删除')
    await loadDevices()
  } catch (err) { ElMessage.error(err.message || '删除失败') }
}

// ======================== 指令下发 ========================
const cmdDialog = reactive({ visible: false, deviceId: '', form: { commandJson: '' }, resultText: '', history: [] })

const openCmdDialog = async (row) => {
  cmdDialog.deviceId = row.deviceId
  cmdDialog.form.commandJson = JSON.stringify({ command: 'set_temp', target: 26 })
  cmdDialog.resultText = ''
  cmdDialog.visible = true
  try {
    const res = await request({ url: '/api/v1/iot/devices/' + row.deviceId + '/commands', baseURL: '', method: 'get', params: { tenantId: '1001', limit: 5 } })
    cmdDialog.history = res.data || []
  } catch { cmdDialog.history = [] }
}

const sendCommand = async () => {
  try {
    const res = await request({
      url: '/api/v1/iot/devices/' + cmdDialog.deviceId + '/command', baseURL: '', method: 'post',
      params: { tenantId: '1001' }, data: { commandJson: cmdDialog.form.commandJson }
    })
    cmdDialog.resultText = res.data.state + ': ' + res.data.detail
    ElMessage.success('指令已处理')
    const hist = await request({ url: '/api/v1/iot/devices/' + cmdDialog.deviceId + '/commands', baseURL: '', method: 'get', params: { tenantId: '1001', limit: 5 } })
    cmdDialog.history = hist.data || []
  } catch (err) { ElMessage.error('下发失败: ' + (err.message || '')) }
}

onMounted(() => {
  loadAnomalies()
  loadProducts()
  loadDevices()
})
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

.cmd-result {
  margin-top: 10px;
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  color: #67c23a;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12.5px;
}

.cmd-history-row {
  font-size: 12px;
  color: #606266;
  margin: 3px 0;
}
</style>
