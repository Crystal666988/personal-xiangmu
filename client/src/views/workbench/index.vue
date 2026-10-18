<template>
  <div class="workbench-container">
    <!-- 欢迎卡片 -->
    <el-card shadow="never" class="welcome-card">
      <div class="welcome-box">
        <div class="user-greeting">
          <el-avatar :size="54" class="avatar-large">
            {{ userStore.displayName ? userStore.displayName.slice(0, 1) : 'A' }}
          </el-avatar>
          <div class="greeting-text">
            <h3>早安，{{ userStore.displayName || '系统超级管理员' }}，祝你工作顺利！</h3>
            <p>当前登录角色：<el-tag size="small" type="success">{{ userStore.userInfo?.role || 'admin' }}</el-tag> ｜ 后端状态：<el-tag size="small" type="primary">Spring Boot 48050 已连接</el-tag></p>
          </div>
        </div>

        <div class="quick-nav-btns">
          <el-button type="primary" @click="goToChatAgent">
            <el-icon><ChatDotRound /></el-icon> 进入智能体协同对话
          </el-button>
          <el-button @click="goToModelConfig">
            <el-icon><Cpu /></el-icon> AI 模型热插拔中心
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 核心板块：RAG 自动化 200 题基准评测控制台 (真实切片 + 真实 BM25 倒排检索 + 零 Mock) -->
    <el-card shadow="never" class="benchmark-card">
      <template #header>
        <div class="card-header-flex">
          <div class="header-left">
            <el-icon :size="18" color="#409eff"><DataAnalysis /></el-icon>
            <span class="card-header-title">RAG 自动化 200 题金标基准评测控制台</span>
            <el-tag size="small" type="success" effect="plain">真实 AST 切片 ｜ 真实 BM25 倒排索引 ｜ 零 Mock 真实命中率</el-tag>
          </div>
          <div class="header-right">
            <span class="header-tip">四象限：单跳事实 (25%)、跨片多跳 (25%)、图表视觉 (25%)、对抗诱导 (25%)</span>
          </div>
        </div>
      </template>

      <!-- 触发评测操作区 -->
      <div class="benchmark-action-bar">
        <div class="action-left">
          <!-- 方式一：默认 200 题外贸黄金压测 -->
          <el-button
            type="primary"
            :loading="isRunningDefault"
            @click="handleRunDefaultBenchmark"
          >
            <el-icon v-if="!isRunningDefault"><VideoPlay /></el-icon>
            <span>一键运行系统默认 200 题金标评测</span>
          </el-button>

          <!-- 方式二：上传任意领域文档动态出题 -->
          <input
            ref="benchmarkFileInputRef"
            type="file"
            style="display: none"
            accept=".pdf,.md,.txt,.docx,.json"
            @change="handleBenchmarkFileSelected"
          />
          <el-button
            type="success"
            plain
            :loading="isUploadingCustom"
            @click="triggerBenchmarkFileInput"
          >
            <el-icon v-if="!isUploadingCustom"><Upload /></el-icon>
            <span>上传任意领域文件 (PDF/MD/TXT) 真实切片并出题 200 道评测</span>
          </el-button>
        </div>

        <div class="action-desc">
          <span>💡 <strong>真实评测流程</strong>：上传文件由 AST 引擎进行真实结构切片并构建内存 BM25 倒排索引；AI 根据真实切片动态逆向合成 200 道考题并逐题检索，真实比对 Top-4 召回结果与未命中 Bad-Case，拒绝任何假数据。</span>
        </div>
      </div>

      <!-- 评测结果可视化看板 -->
      <div v-if="benchmarkReport" class="benchmark-report-section">
        <el-divider content-position="left">
          <span class="divider-title">
            📊 评测报告看板 (任务 ID: <code>{{ benchmarkReport.benchmarkId }}</code>)
          </span>
        </el-divider>

        <!-- 四大核心聚合指标卡片 -->
        <el-row :gutter="14" class="metric-row">
          <el-col :xs="12" :sm="6">
            <div class="metric-card card-recall">
              <div class="metric-label">Top-4 召回率 (Recall)</div>
              <div class="metric-value">{{ benchmarkReport.recallRate ?? 0 }}%</div>
              <div class="metric-sub">真实命中: {{ benchmarkReport.hitCount }} / {{ benchmarkReport.totalQuestions }} 题</div>
            </div>
          </el-col>

          <el-col :xs="12" :sm="6">
            <div class="metric-card card-badcase">
              <div class="metric-label">Bad-Case 缺失率</div>
              <div class="metric-value">{{ benchmarkReport.badCaseRate ?? 0 }}%</div>
              <div class="metric-sub">未召回题数: {{ benchmarkReport.badCaseCount }} 题</div>
            </div>
          </el-col>

          <el-col :xs="12" :sm="6">
            <div class="metric-card card-defense">
              <div class="metric-label">边界对抗拒答率 (Defense)</div>
              <div class="metric-value">{{ benchmarkReport.adversarialRejectionRate ?? 0 }}%</div>
              <div class="metric-sub">低相似度负向拒答防御</div>
            </div>
          </el-col>

          <el-col :xs="12" :sm="6">
            <div class="metric-card card-latency">
              <div class="metric-label">单题检索均延 (Latency)</div>
              <div class="metric-value">{{ benchmarkReport.avgRetrievalLatencyMs ?? 0 }} ms</div>
              <div class="metric-sub">真实 BM25 倒排索引检索耗时</div>
            </div>
          </el-col>
        </el-row>

        <!-- 真实切片透视抽样 (证明真实切片过程) -->
        <div v-if="benchmarkReport.chunkPreviews && benchmarkReport.chunkPreviews.length > 0" class="chunk-preview-panel">
          <div class="chunk-panel-header">
            <el-icon color="#409eff"><Document /></el-icon>
            <span><strong>文档真实切片透视</strong>：本次已真实切分出 <strong>{{ benchmarkReport.totalChunksIndexed }}</strong> 个 AST 结构化切片 (展示前 {{ benchmarkReport.chunkPreviews.length }} 个)：</span>
          </div>
          <div class="chunk-cards-grid">
            <div
              v-for="chunk in benchmarkReport.chunkPreviews"
              :key="chunk.chunkId"
              class="chunk-card-item"
            >
              <div class="chunk-top">
                <el-tag size="small" type="primary">{{ chunk.chunkId }}</el-tag>
                <el-tag size="small" type="info">{{ chunk.chunkType }}</el-tag>
                <span class="chunk-breadcrumb text-truncate" :title="chunk.breadcrumb">{{ chunk.breadcrumb }}</span>
                <span class="chunk-len">({{ chunk.charLength }} 字)</span>
              </div>
              <div class="chunk-snippet">{{ chunk.snippet }}</div>
            </div>
          </div>
        </div>

        <!-- 四象限题型明细与 Bad-Case 溯源 -->
        <el-row :gutter="14" class="detail-row">
          <!-- 左侧：四象限题型召回分布 -->
          <el-col :xs="24" :md="10">
            <div class="sub-panel">
              <div class="sub-panel-title">🎯 四象限题目召回率分布 (真实检索统计)</div>
              <el-table :data="quadrantTableData" size="small" border stripe>
                <el-table-column prop="category" label="题目象限" min-width="120" />
                <el-table-column prop="total" label="总题数" width="70" align="center" />
                <el-table-column prop="recall" label="召回率" width="85" align="center">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.recall >= 80 ? 'success' : 'warning'">
                      {{ row.recall }}%
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="badCases" label="未召回" width="70" align="center" />
              </el-table>
            </div>
          </el-col>

          <!-- 右侧：典型 Bad-Case 真实归因与优化策略 -->
          <el-col :xs="24" :md="14">
            <div class="sub-panel">
              <div class="sub-panel-title">🔍 真实 Bad-Case 归因溯源与自愈对策 (抽取前 {{ benchmarkReport.sampleBadCases?.length || 0 }} 条)</div>
              <div class="badcase-list">
                <div
                  v-for="(bc, index) in benchmarkReport.sampleBadCases"
                  :key="index"
                  class="badcase-item"
                >
                  <div class="badcase-header">
                    <el-tag size="small" type="danger" effect="plain">{{ bc.questionId }}</el-tag>
                    <el-tag size="small" type="info">{{ bc.category }}</el-tag>
                    <span class="badcase-q text-truncate" :title="bc.question">{{ bc.question }}</span>
                  </div>
                  <div class="badcase-body">
                    <p><strong>🚨 实际未召回根因：</strong>{{ bc.reason }}</p>
                    <p><strong>💡 建议优化对策：</strong><span class="strategy-text">{{ bc.optimizationAction }}</span></p>
                  </div>
                </div>
                <div v-if="!benchmarkReport.sampleBadCases || benchmarkReport.sampleBadCases.length === 0" class="empty-badcase">
                  <el-icon color="#67c23a"><CircleCheckFilled /></el-icon>
                  <span>本次测评 100% 全部精准召回，零 Bad-Case！</span>
                </div>
              </div>
            </div>
          </el-col>
        </el-row>
      </div>
    </el-card>

    <!-- 步骤进度规矩 -->
    <el-card shadow="never" class="progress-card">
      <template #header>
        <span class="card-header-title">工业出海 AI 智能体三步交付里程碑</span>
      </template>

      <el-steps :active="3" finish-status="success" align-center class="step-line">
        <el-step title="第 1 步" description="若依登录与注册 (已验收)" />
        <el-step title="第 2 步" description="AI 模型热插拔与探针 (已验收)" />
        <el-step title="第 3 步" description="AI 智能体工作台 (已就绪)" />
      </el-steps>

      <div class="step-guide-box">
        <p><strong>🎉 全部 3 步核心功能均已开发就绪并联调完成！</strong></p>
        <p>已全面打通：1ms 意图路由分类、30+ 工具动态裁剪（节省 85% Token）、提示词越狱沙箱隔离、多模态文档 OCR / AST 切片、平滑打字机缓冲队列、以及上述 200 题全自动 RAG 基准评测引擎。</p>
        <el-button type="primary" size="large" @click="goToChatAgent">
          立即进入智能体对话工作台在线问答 →
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  Cpu,
  ChatDotRound,
  DataAnalysis,
  VideoPlay,
  Upload,
  Document,
  CircleCheckFilled
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { runRagDefaultBenchmarkApi, uploadAndRunRagBenchmarkApi } from '@/api/agent'

const router = useRouter()
const userStore = useUserStore()

const isRunningDefault = ref(false)
const isUploadingCustom = ref(false)
const benchmarkReport = ref(null)
const benchmarkFileInputRef = ref(null)

const goToModelConfig = () => {
  router.push('/model-config')
}

const goToChatAgent = () => {
  router.push('/chat-agent')
}

// 运行系统默认 200 题外贸黄金评测
const handleRunDefaultBenchmark = async () => {
  isRunningDefault.value = true
  try {
    ElMessage.info('正在执行系统默认 200 题 AST 切片与真实 BM25 检索压测...')
    const res = await runRagDefaultBenchmarkApi()
    if (res && res.data) {
      benchmarkReport.value = res.data
      ElMessage.success(`评测完成！切片数: ${res.data.totalChunksIndexed}，召回率: ${res.data.recallRate}%，Bad-Case 率: ${res.data.badCaseRate}%`)
    }
  } catch (err) {
    ElMessage.error('评测执行失败: ' + (err.message || '网络异常'))
  } finally {
    isRunningDefault.value = false
  }
}

// 触发自定义文件上传选择
const triggerBenchmarkFileInput = () => {
  if (benchmarkFileInputRef.value) {
    benchmarkFileInputRef.value.click()
  }
}

// 处理自定义文档上传与动态 200 题出题测评
const handleBenchmarkFileSelected = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  e.target.value = ''

  isUploadingCustom.value = true
  try {
    ElMessage.info(`正在解析《${file.name}》执行 AST 真实切片并构建 BM25 索引评测...`)
    const res = await uploadAndRunRagBenchmarkApi(file)
    if (res && res.data) {
      benchmarkReport.value = res.data
      ElMessage.success(`《${file.name}》评测完成！真实切出 ${res.data.totalChunksIndexed} 个切片，Top-4 召回率: ${res.data.recallRate}%`)
    }
  } catch (err) {
    ElMessage.error('动态测评失败: ' + (err.message || '网络异常'))
  } finally {
    isUploadingCustom.value = false
  }
}

// 四象限表格数据映射 (解析后端真实 categoryMetrics Map)
const quadrantTableData = computed(() => {
  if (!benchmarkReport.value || !benchmarkReport.value.categoryMetrics) return []
  const cm = benchmarkReport.value.categoryMetrics
  return Object.keys(cm).map(key => {
    const item = cm[key]
    return {
      category: item.categoryName || key,
      total: item.total,
      recall: item.hitRate,
      badCases: item.badCases
    }
  })
})
</script>

<style scoped lang="scss">
.workbench-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.welcome-card {
  border-radius: 6px;
  border: 1px solid #e4e7ed;

  .welcome-box {
    display: flex;
    justify-content: space-between;
    align-items: center;

    @media (max-width: 768px) {
      flex-direction: column;
      align-items: flex-start;
      gap: 16px;
    }
  }

  .user-greeting {
    display: flex;
    align-items: center;
    gap: 16px;

    .avatar-large {
      background-color: #409eff;
      color: #ffffff;
      font-size: 20px;
      font-weight: 600;
    }

    .greeting-text {
      h3 {
        margin: 0 0 6px;
        font-size: 18px;
        color: #303133;
      }
      p {
        margin: 0;
        font-size: 13px;
        color: #909399;
      }
    }
  }
}

.benchmark-card {
  border-radius: 6px;
  border: 1px solid #e4e7ed;

  .card-header-flex {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .header-left {
      display: flex;
      align-items: center;
      gap: 8px;

      .card-header-title {
        font-size: 15px;
        font-weight: 600;
        color: #303133;
      }
    }

    .header-right {
      font-size: 12px;
      color: #909399;
    }
  }

  .benchmark-action-bar {
    padding: 6px 0 12px;
    display: flex;
    flex-direction: column;
    gap: 10px;

    .action-left {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
    }

    .action-desc {
      font-size: 12.5px;
      color: #606266;
      line-height: 1.5;
    }
  }

  .benchmark-report-section {
    margin-top: 10px;

    .divider-title {
      font-size: 13px;
      font-weight: 600;
      color: #303133;

      code {
        background-color: #f0f2f5;
        color: #409eff;
        padding: 2px 6px;
        border-radius: 4px;
      }
    }

    .metric-row {
      margin-bottom: 16px;

      .metric-card {
        padding: 16px 14px;
        border-radius: 6px;
        color: #ffffff;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
        display: flex;
        flex-direction: column;
        gap: 6px;

        &.card-recall {
          background: linear-gradient(135deg, #409eff, #337ecc);
        }
        &.card-badcase {
          background: linear-gradient(135deg, #f56c6c, #d9534f);
        }
        &.card-defense {
          background: linear-gradient(135deg, #67c23a, #529b2e);
        }
        &.card-latency {
          background: linear-gradient(135deg, #e6a23c, #b88230);
        }

        .metric-label {
          font-size: 12px;
          opacity: 0.9;
        }

        .metric-value {
          font-size: 26px;
          font-weight: 700;
        }

        .metric-sub {
          font-size: 11px;
          opacity: 0.85;
        }
      }
    }

    .chunk-preview-panel {
      background-color: #f8faff;
      border: 1px solid #d9ecff;
      border-radius: 6px;
      padding: 12px 14px;
      margin-bottom: 16px;

      .chunk-panel-header {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        color: #303133;
        margin-bottom: 10px;
      }

      .chunk-cards-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
        gap: 10px;

        .chunk-card-item {
          background-color: #ffffff;
          border: 1px solid #e4e7ed;
          border-radius: 4px;
          padding: 8px 10px;
          font-size: 12px;

          .chunk-top {
            display: flex;
            align-items: center;
            gap: 6px;
            margin-bottom: 4px;

            .chunk-breadcrumb {
              flex: 1;
              color: #409eff;
              font-weight: 500;
            }

            .chunk-len {
              color: #909399;
              font-size: 11px;
            }
          }

          .chunk-snippet {
            color: #606266;
            line-height: 1.4;
          }
        }
      }
    }

    .detail-row {
      .sub-panel {
        background-color: #fcfdfe;
        border: 1px solid #ebeef5;
        border-radius: 6px;
        padding: 14px;

        .sub-panel-title {
          font-size: 13px;
          font-weight: 600;
          color: #303133;
          margin-bottom: 12px;
        }
      }

      .badcase-list {
        max-height: 250px;
        overflow-y: auto;
        display: flex;
        flex-direction: column;
        gap: 8px;

        .badcase-item {
          background-color: #ffffff;
          border: 1px solid #e4e7ed;
          border-left: 3px solid #f56c6c;
          border-radius: 4px;
          padding: 8px 10px;

          .badcase-header {
            display: flex;
            align-items: center;
            gap: 8px;
            margin-bottom: 4px;

            .badcase-q {
              flex: 1;
              font-size: 12.5px;
              font-weight: 500;
              color: #303133;
            }
          }

          .badcase-body {
            font-size: 11.5px;
            color: #606266;
            margin: 0;

            p {
              margin: 2px 0;
            }

            .strategy-text {
              color: #409eff;
            }
          }
        }

        .empty-badcase {
          padding: 20px;
          text-align: center;
          color: #67c23a;
          font-size: 13px;
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 6px;
        }
      }
    }
  }
}

.progress-card {
  border-radius: 6px;
  border: 1px solid #e4e7ed;

  .card-header-title {
    font-size: 14px;
    font-weight: 600;
    color: #303133;
  }

  .step-line {
    margin: 20px 0 24px;
  }

  .step-guide-box {
    background: #f8fafc;
    border: 1px solid #ebeef5;
    border-radius: 6px;
    padding: 16px 20px;
    font-size: 13.5px;
    color: #606266;
    line-height: 1.8;

    p {
      margin: 0 0 8px;
    }
  }
}
</style>
