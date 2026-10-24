<template>
  <div class="feishu-container">
    <!-- 常见飞书应用场景（蹭风口清单） -->
    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text">
            <el-icon><Promotion /></el-icon> 常见飞书应用场景
          </span>
          <el-tag size="small" type="warning" effect="plain">接入真实飞书需企业自建应用凭证</el-tag>
        </div>
      </template>
      <el-row :gutter="14">
        <el-col v-for="app in scenarios" :key="app.name" :xs="24" :sm="12" :md="8">
          <div class="scenario-card">
            <div class="scenario-head">
              <span class="scenario-name">{{ app.name }}</span>
              <el-tag size="small" :type="app.demo ? 'success' : 'info'" effect="plain">
                {{ app.demo ? '本项目已演示' : '可接入' }}
              </el-tag>
            </div>
            <div class="scenario-desc">{{ app.desc }}</div>
            <div class="scenario-need">接入要求：{{ app.need }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 真实链路 1：云文档 → 知识库 -->
    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text">
            <el-icon><Document /></el-icon> 链路一：飞书云文档 → RAG 知识库同步
          </span>
          <el-tag size="small" type="success" effect="plain">切片管道真实执行</el-tag>
        </div>
      </template>
      <el-form label-width="130px" size="default" class="inline-form">
        <el-form-item label="文档 Token">
          <el-input v-model="docsForm.documentToken" placeholder="飞书云文档 Token (如 doxcnABC123)" style="width: 320px" />
        </el-form-item>
        <el-form-item label="内部文档 ID">
          <el-input v-model="docsForm.documentId" placeholder="知识库内部文档 ID (默认 1001)" style="width: 320px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="docsLoading" @click="runDocsSync">
            <el-icon><Refresh /></el-icon> 同步并切片
          </el-button>
        </el-form-item>
        <el-form-item v-if="docsResult">
          <el-alert type="success" :closable="false" show-icon :title="docsResult" />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 真实链路 2：Bitable 商机回写 -->
    <el-card shadow="never" class="box-card">
      <template #header>
        <div class="card-title-bar">
          <span class="title-text">
            <el-icon><Tickets /></el-icon> 链路二：多维表格商机 → 自动背调回写
          </span>
          <el-tag size="small" type="success" effect="plain">Webhook 链路真实触发</el-tag>
        </div>
      </template>
      <el-form label-width="130px" size="default" class="inline-form">
        <el-form-item label="商机记录 ID">
          <el-input v-model="bitableForm.recordId" placeholder="rec_xxx" style="width: 320px" />
        </el-form-item>
        <el-form-item label="公司名称">
          <el-input v-model="bitableForm.companyName" placeholder="海外采购商名称" style="width: 320px" />
        </el-form-item>
        <el-form-item label="公司官网">
          <el-input v-model="bitableForm.website" placeholder="https://example.com" style="width: 320px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="bitableLoading" @click="runBitable">
            <el-icon><Promotion /></el-icon> 模拟 Webhook 触发背调
          </el-button>
        </el-form-item>
        <el-form-item v-if="bitableResult">
          <el-alert type="success" :closable="false" show-icon :title="bitableResult" />
        </el-form-item>
      </el-form>
    </el-card>

    <div class="page-tip">
      演示声明：切片入库与 Webhook 编排链路为真实后端执行；文档拉取与表格回写在演示环境使用固定样例数据，
      生产接入需要飞书企业自建应用（App ID/Secret + tenant_access_token）并按开放平台契约校验签名。
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Promotion, Refresh, Tickets } from '@element-plus/icons-vue'
import { feishuDocsSyncApi, feishuBitableWebhookApi } from '@/api/feishu'

const scenarios = [
  { name: '云文档 → RAG 知识库', desc: '飞书 Docs 增量拉取，AST 切片后进向量库，群内/对话直接问答企业知识', need: '自建应用 + 文档读权限', demo: true },
  { name: '多维表格 CRM 商机背调', desc: '销售在 Bitable 新增商机 → Webhook 触发 Agent 海关/官网背调 → 结果回写表格列', need: '自建应用 + Bitable 读写权限', demo: true },
  { name: 'IoT 设备异常人工预警', desc: '养殖/工厂智能设备经 MQTT 入库，阈值越界实时推送飞书预警卡片，需人工确认处理', need: '群机器人 Webhook 地址', demo: true },
  { name: '群机器人告警与日报', desc: 'Incoming Webhook 推送汇率异动、待跟进提醒、成交战报卡片，30 分钟即可接入', need: '群机器人 Webhook 地址', demo: false },
  { name: '审批 AI 摘要机器人', desc: '接入审批事件，AI 自动摘要报销/出差/折扣审批单并推送处理建议', need: '审批权限 + 事件订阅', demo: false },
  { name: '把本 Agent 接进飞书对话', desc: '企业自建应用长连接（WebSocket），在飞书聊天里直接 @ 本系统问答', need: '自建应用 + 事件订阅长连接', demo: false },
  { name: '每日外贸战报定时推送', desc: '定时任务聚合汇率、海关动态、公海客户数据，生成卡片推送到管理层群', need: '群机器人 + 定时任务', demo: false }
]

const docsForm = reactive({ documentToken: 'doxcnABC123456', documentId: 1001 })
const bitableForm = reactive({ recordId: 'rec_demo_001', companyName: 'Al-Futtaim Solar Energy', website: 'https://al-futtaim-solar.ae' })

const docsLoading = ref(false)
const docsResult = ref('')
const bitableLoading = ref(false)
const bitableResult = ref('')

const runDocsSync = async () => {
  docsLoading.value = true
  docsResult.value = ''
  try {
    const res = await feishuDocsSyncApi(docsForm.documentToken, docsForm.documentId)
    docsResult.value = res.data || '同步完成'
    ElMessage.success('云文档同步成功')
  } catch (err) {
    docsResult.value = ''
    ElMessage.error('同步失败: ' + err.message)
  } finally {
    docsLoading.value = false
  }
}

const runBitable = async () => {
  bitableLoading.value = true
  bitableResult.value = ''
  try {
    const res = await feishuBitableWebhookApi(bitableForm)
    bitableResult.value = res.data || 'Webhook 已触发'
    ElMessage.success('Webhook 链路已触发')
  } catch (err) {
    bitableResult.value = ''
    ElMessage.error('触发失败: ' + err.message)
  } finally {
    bitableLoading.value = false
  }
}
</script>

<style scoped lang="scss">
.feishu-container {
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
  }
}

.scenario-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px 14px;
  margin-bottom: 14px;
  background: #fafbfc;
  min-height: 118px;

  .scenario-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 6px;

    .scenario-name {
      font-size: 13.5px;
      font-weight: 600;
      color: #303133;
    }
  }

  .scenario-desc {
    font-size: 12.5px;
    color: #606266;
    line-height: 1.6;
    margin-bottom: 6px;
  }

  .scenario-need {
    font-size: 11.5px;
    color: #909399;
  }
}

.inline-form {
  max-width: 640px;
}

.page-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  padding: 0 4px;
}
</style>
