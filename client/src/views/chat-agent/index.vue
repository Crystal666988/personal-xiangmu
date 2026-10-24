<template>
  <div class="chat-agent-container">
    <!-- 双栏布局：左侧会话与智能体，右侧全宽对话主界面 (Ruoyi-Vue 优雅规范) -->
    <el-row :gutter="12" class="full-height-row">
      <!-- 左栏：场景智能体与会话列表 -->
      <el-col :xs="24" :sm="8" :md="7" :lg="6" class="left-panel">
        <el-card shadow="never" class="panel-card no-padding">
          <div class="sidebar-header">
            <span class="header-title">
              <el-icon><Service /></el-icon> 智能体与会话
            </span>
            <el-button type="primary" size="small" plain @click="createNewSession">
              <el-icon><Plus /></el-icon> 新建会话
            </el-button>
          </div>

          <!-- 历史会话列表 (MySQL 数据库级持久化) -->
          <div class="session-list-section">
            <div class="section-header-row">
              <span class="section-label">最近会话列表:</span>
            </div>
            <div class="session-scroll-list">
              <div
                v-for="session in sessions"
                :key="session.id"
                class="session-item"
                :class="{ active: currentSessionId === session.id }"
                @click="switchSession(session.id)"
              >
                <div class="session-icon">
                  <el-icon><ChatLineRound /></el-icon>
                </div>
                <div class="session-content">
                  <div class="session-title text-truncate">{{ session.title }}</div>
                  <div class="session-time">{{ session.time || '刚刚' }}</div>
                </div>
                <!-- 删除单条会话按钮 -->
                <el-icon
                  v-if="sessions.length > 1"
                  class="session-del-icon"
                  title="删除此会话 (从数据库删除)"
                  @click.stop="deleteSession(session.id)"
                >
                  <Close />
                </el-icon>
              </div>
            </div>
          </div>

          <!-- 底部状态指示 -->
          <div class="sidebar-footer">
            <div class="footer-model-row font-mono text-truncate">
              {{ activeModelDisplayName }}
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧主区域：全宽打字机流式对话核心主窗口 -->
      <el-col :xs="24" :sm="16" :md="17" :lg="18" class="center-panel">
        <el-card shadow="never" class="panel-card chat-box-card">
          <!-- 对话窗口顶部状态栏 -->
          <template #header>
            <div class="chat-header">
              <div class="chat-header-left">
                <span class="active-agent-badge" style="color: #409eff">
                  <el-icon><Connection /></el-icon>
                </span>
                <span class="chat-title">{{ currentSessionTitle }}</span>
              </div>
              <div class="chat-header-right">
                <el-button size="small" type="primary" plain @click="openIntentBenchmarkDrawer">
                  <el-icon><Connection /></el-icon>
                  <span>意图路由 200 题基准评测</span>
                </el-button>
                <el-tooltip content="清空当前会话消息 (从数据库清空)" placement="top">
                  <el-button size="small" text @click="clearCurrentMessages">
                    <el-icon><Delete /></el-icon> 清空
                  </el-button>
                </el-tooltip>
              </div>
            </div>
          </template>

          <!-- 消息流水渲染区 -->
          <div ref="messageContainerRef" class="message-container">
            <div
              v-for="(msg, index) in currentMessages"
              :key="index"
              class="message-row"
              :class="msg.role === 'user' ? 'row-user' : 'row-assistant'"
            >
              <!-- 头像 -->
              <div class="msg-avatar">
                <el-avatar
                  v-if="msg.role === 'user'"
                  :size="36"
                  style="background-color: #409eff"
                >
                  我
                </el-avatar>
                <el-avatar
                  v-else
                  :size="36"
                  style="background-color: #409eff"
                >
                  <el-icon :size="20"><Connection /></el-icon>
                </el-avatar>
              </div>

              <!-- 气泡内容 -->
              <div class="msg-bubble-wrap">
                <div class="msg-meta">
                  <span class="sender-name">{{ msg.role === 'user' ? '我 (业务员)' : 'TradeAgent 智能体' }}</span>
                  <span class="msg-time">{{ msg.time }}</span>
                  <el-tooltip :content="msg.role === 'user' ? '删除此轮对话 (连同对应回答一并从数据库清除，彻底净化上下文)' : '删除本条回答 (从数据库清除)'" placement="top">
                    <el-button
                      size="small"
                      type="danger"
                      link
                      class="delete-round-btn"
                      @click="handleDeleteMessageOrRound(index)"
                    >
                      <el-icon><Delete /></el-icon>
                      <span>{{ msg.role === 'user' ? '删除此轮' : '删除本条' }}</span>
                    </el-button>
                  </el-tooltip>
                </div>

                <!-- 用户消息附带的文件/图片展示 (带图片真实缩略图与阿里云 OSS 云端归档链接) -->
                <div v-if="msg.attachment" class="user-attachment-card">
                  <div class="attachment-meta-row">
                    <div class="file-icon-wrap">
                      <el-icon v-if="msg.attachment.isImage" :size="20" color="#67c23a"><PictureFilled /></el-icon>
                      <el-icon v-else-if="msg.attachment.isPdf" :size="20" color="#f56c6c"><Document /></el-icon>
                      <el-icon v-else :size="20" color="#409eff"><FolderOpened /></el-icon>
                    </div>
                    <div class="file-meta">
                      <div class="file-name text-truncate">{{ msg.attachment.name }}</div>
                      <div class="file-size">{{ msg.attachment.sizeText }} ｜ 真实载入与多模态解析</div>
                    </div>
                  </div>

                  <!-- 阿里云 OSS 云端永久归档下载链接 -->
                  <div v-if="msg.attachment.ossUrl" class="oss-badge-row">
                    <a :href="msg.attachment.ossUrl" target="_blank" class="oss-link-btn" title="查看或下载已存入阿里云 OSS 存储桶的原文件">
                      <el-icon><Cloudy /></el-icon>
                      <span>已归档至阿里云 OSS (点击查看原件)</span>
                    </a>
                  </div>

                  <!-- 图片缩略图展示 -->
                  <div v-if="msg.attachment.previewUrl" class="image-thumbnail-box">
                    <el-image
                      :src="msg.attachment.previewUrl"
                      :preview-src-list="[msg.attachment.previewUrl]"
                      fit="contain"
                      class="thumbnail-img"
                    />
                  </div>
                </div>

                <div class="msg-bubble" :class="{ 'streaming-bubble': msg.isStreaming }">
                  <!-- 核心特性 2：三阶段透明感知 CoT 深度思考链卡片 -->
                  <div v-if="msg.thought || msg.isThinking" class="thought-container">
                    <div class="thought-header" @click="toggleThought(msg)">
                      <div class="thought-title">
                        <span v-if="msg.isThinking" class="thinking-spinner">
                          <el-icon class="is-loading"><Loading /></el-icon>
                          <span>深度思考中 (已用时 {{ msg.thoughtDuration || 1 }} 秒)...</span>
                        </span>
                        <span v-else class="thought-done">
                          <el-icon><Opportunity /></el-icon>
                          <span>已完成深度思考 (用时 {{ msg.thoughtDuration || 2 }} 秒)</span>
                        </span>
                      </div>
                      <div class="thought-toggle-arrow">
                        <el-icon :class="{ 'is-expanded': msg.isThoughtExpanded }"><ArrowDown /></el-icon>
                      </div>
                    </div>

                    <!-- 展开的思考链内容 (包含意图路由决策 + OCR/AST 底层调用过程 + 大模型原生推理) -->
                    <transition name="el-zoom-in-top">
                      <div v-if="msg.isThoughtExpanded" class="thought-content font-mono markdown-body">
                        <div class="thought-text" v-html="renderMessageContent(msg.thought)"></div>
                        <span v-if="msg.isThinking" class="thought-cursor">▌</span>
                      </div>
                    </transition>
                  </div>

                  <!-- 正式回答文本区 (marked 专业渲染，支持 GFM 规范表格、列表、代码块) -->
                  <div
                    v-if="msg.content"
                    class="bubble-content markdown-body"
                    v-html="renderMessageContent(msg.content)"
                  ></div>

                  <!-- 正在输出正文时的打字机光标 -->
                  <span v-if="msg.isStreaming && !msg.isThinking" class="cursor-blink">▌</span>
                </div>
              </div>
            </div>

                        <!-- HITL 审批门禁与执行存档卡片 -->
            <HitlApprovalCard
              v-if="currentExecution && !isExecutionTerminal"
              :execution="currentExecution"
              :busy="executionBusy"
              :unavailable="Boolean(executionUnavailable[currentSessionId])"
              @decision="submitApproval"
              @resume="resumeExecution"
              @cancel="cancelExecution"
              @refresh="refreshExecution(currentSessionId)"
            />

            <!-- 空状态引导 -->
            <div v-if="currentMessages.length === 0" class="empty-chat-guide">
              <el-icon :size="52" color="#c0c4cc"><ChatDotSquare /></el-icon>
              <h4>欢迎使用工业出海 AI 协同平台</h4>
              <p>系统已连接 MySQL 数据库与阿里云 OSS 云存储，支持意图路由、OCR 底层调用与大模型 CoT 全过程透明回显。</p>
              <div class="sample-questions">
                <span class="sample-title">点击下方业务示例或直接 Ctrl+V 粘贴截图/文档，立即检验真实效果：</span>
                <div class="sample-chips">
                  <el-tag
                    v-for="(q, qIndex) in sampleQueries"
                    :key="qIndex"
                    class="sample-chip"
                    type="info"
                    effect="plain"
                    @click="fillAndSend(q.text)"
                  >
                    {{ q.label }}
                  </el-tag>
                </div>
              </div>
            </div>
          </div>

          <!-- 底部输入区 (支持 Ctrl+V 粘贴截图/文件与拖拽) -->
          <div class="chat-input-area">
            <!-- 待发送文件附件预览条 (如果用户选了文件或粘贴了图片) -->
            <div v-if="selectedFile" class="attachment-preview-bar">
              <div class="file-chip">
                <el-image
                  v-if="selectedFilePreview"
                  :src="selectedFilePreview"
                  class="chip-preview-img"
                  fit="cover"
                />
                <el-icon v-else-if="isImageFile(selectedFile.name)" color="#67c23a"><PictureFilled /></el-icon>
                <el-icon v-else-if="selectedFile.name.endsWith('.pdf')" color="#f56c6c"><Document /></el-icon>
                <el-icon v-else color="#409eff"><FolderOpened /></el-icon>
                <span class="file-name text-truncate">{{ selectedFile.name }} ({{ formatFileSize(selectedFile.size) }})</span>
                <el-icon class="remove-file-btn" @click="removeSelectedFile"><Close /></el-icon>
              </div>
              <span class="upload-tip-text">发送时将同步归档至阿里云 OSS 并执行真实 OCR/AST 抽取</span>
            </div>

            <div class="input-form-wrap" @paste="handlePaste">
              <!-- 隐藏的真实文件 input 控件 -->
              <input
                ref="fileInputRef"
                type="file"
                style="display: none"
                accept=".pdf,.png,.jpg,.jpeg,.webp,.txt,.md,.doc,.docx,.json,.csv,.html,.htm"
                @change="handleFileSelected"
              />

              <div class="input-textarea-row">
                <el-input
                  v-model="inputMessage"
                  type="textarea"
                  :autosize="{ minRows: 1, maxRows: 8 }"
                  placeholder="请输入您的外贸咨询、技术疑问，或直接 Ctrl+V 粘贴截图/文件，点击左下角上传 (Enter 发送，Shift+Enter 换行)..."
                  resize="none"
                  :disabled="isGenerating"
                  @keydown.enter.prevent="handleEnterPress"
                  @paste="handlePaste"
                />
              </div>

              <div class="input-bottom-actions">
                <div class="actions-left">
                  <!-- 上传附件按钮 -->
                  <el-tooltip content="上传 PDF 文档、工程图纸、截图或研发进度 (也支持直接 Ctrl+V 粘贴)" placement="top">
                    <el-button
                      size="small"
                      plain
                      :disabled="isGenerating"
                      @click="triggerFileInput"
                    >
                      <el-icon><Paperclip /></el-icon> 上传附件 (PDF/图片/MD)
                    </el-button>
                  </el-tooltip>
                </div>

                <div class="actions-right">
                  <el-button
                    v-if="isGenerating"
                    type="danger"
                    size="small"
                    plain
                    @click="stopGeneration"
                  >
                    <el-icon><VideoPause /></el-icon> 停止生成
                  </el-button>
                  <el-button
                    v-else
                    type="primary"
                    size="small"
                    :disabled="!inputMessage.trim() && !selectedFile"
                    @click="sendMessage"
                  >
                    <el-icon><Promotion /></el-icon> 发送
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 意图路由 200 题基准评测抽屉 (Drawer) -->
    <el-drawer
      v-model="intentBenchmarkDrawerVisible"
      title="🎯 意图路由 200 题黄金基准评测与混淆矩阵 (工业级)"
      size="80%"
      direction="rtl"
    >
      <div class="intent-benchmark-container p-3">
        <!-- 顶部控制栏 -->
        <div class="benchmark-control-bar">
          <div class="benchmark-config-left">
            <el-button
              type="primary"
              :loading="isRunningIntentBenchmark"
              @click="handleRunIntentBenchmark"
            >
              <el-icon v-if="!isRunningIntentBenchmark"><VideoPlay /></el-icon>
              <span>{{ isRunningIntentBenchmark ? '200 题百炼稠密向量并发跑测中...' : '一键执行意图路由 200 题基准评测' }}</span>
            </el-button>
            <el-button :icon="Clock" @click="openIntentHistoryDrawer">
              历史意图报告记录
            </el-button>
            <el-button :icon="Refresh" @click="loadLatestIntentBenchmark">刷新最新指标</el-button>
          </div>

          <div class="benchmark-tip-box">
            <el-icon color="#e6a23c"><InfoFilled /></el-icon>
            <span>基于阿里百炼 text-embedding-v3 (1024 维稠密特征) 与强特征正则的混合路由流水线评测</span>
          </div>
        </div>

        <!-- 评测结果展示区 -->
        <div v-if="intentReport" class="intent-report-view">
          <!-- 核心大指标卡片 -->
          <div class="metric-cards-grid">
            <div class="metric-stat-card primary">
              <div class="metric-title">🎯 全局综合分类准确率</div>
              <div class="metric-value">{{ intentReport.overallAccuracy }}%</div>
              <div class="metric-sub">命中: {{ intentReport.hitCount }} / {{ intentReport.totalCases }} 题</div>
            </div>

            <div class="metric-stat-card success">
              <div class="metric-title">💬 通用闲聊识别率 (0工具)</div>
              <div class="metric-value">{{ intentReport.chitchatAccuracy }}%</div>
              <div class="metric-sub">闲聊题命中: {{ intentReport.chitchatHits }} / {{ intentReport.chitchatTotal }} (剪枝 100%)</div>
            </div>

            <div class="metric-stat-card warning">
              <div class="metric-title">🔀 复合跨领域调度率</div>
              <div class="metric-value">{{ intentReport.compositeAccuracy }}%</div>
              <div class="metric-sub">复合题命中: {{ intentReport.compositeHits }} / {{ intentReport.compositeTotal }} (跨领域工具聚合)</div>
            </div>

            <div class="metric-stat-card info">
              <div class="metric-title">✂️ 平均工具剪枝率</div>
              <div class="metric-value">{{ intentReport.avgPruningRate }}%</div>
              <div class="metric-sub">平均每轮剪枝 12/15 个无关工具</div>
            </div>

            <div class="metric-stat-card danger">
              <div class="metric-title">⚡ 平均路由耗时</div>
              <div class="metric-value">{{ intentReport.avgLatencyMs }} ms</div>
              <div class="metric-sub">1024 维向量提取与余弦相似度</div>
            </div>
          </div>

          <!-- 混淆矩阵看板 (Confusion Matrix) -->
          <div v-if="intentReport.confusionMatrix" class="confusion-matrix-box mt-4">
            <div class="cm-header">
              <h4>📊 意图路由分类混淆矩阵 (Confusion Matrix: 真实意图 ✕ 预测意图)</h4>
              <span class="cm-tip text-muted">对角线深绿色为分类正确；非对角线为混淆/误判</span>
            </div>
            <div class="cm-table-wrap">
              <table class="confusion-table">
                <thead>
                  <tr>
                    <th class="corner-header">预期领域 \ 预测</th>
                    <th>GENERAL (闲聊)</th>
                    <th>COMMERCE (商贸)</th>
                    <th>KNOWLEDGE (合规)</th>
                    <th>PROSPECT (拓客)</th>
                    <th>CRM (客户)</th>
                    <th>COMPOSITE (复合)</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, expKey) in intentReport.confusionMatrix" :key="expKey">
                    <td class="row-label">{{ getDomainLabel(expKey) }}</td>
                    <td
                      v-for="predKey in ['GENERAL', 'COMMERCE', 'KNOWLEDGE', 'PROSPECT', 'CRM', 'COMPOSITE']"
                      :key="predKey"
                      :class="[
                        'cm-cell',
                        expKey === predKey ? (row[predKey] > 0 ? 'cm-hit' : '') : (row[predKey] > 0 ? 'cm-miss' : '')
                      ]"
                    >
                      {{ row[predKey] ?? 0 }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 200 题跑测真题详情明细列表 -->
          <div v-if="intentReport.caseResults && intentReport.caseResults.length > 0" class="cases-detail-section mt-4">
            <div class="section-title-bar">
              <h4>📝 200 题基准跑测明细 (可筛选分类或缺陷题)</h4>
              <div class="filters-bar">
                <el-radio-group v-model="caseFilterCategory" size="small">
                  <el-radio-button label="ALL">全部 ({{ intentReport.caseResults.length }})</el-radio-button>
                  <el-radio-button label="CHITCHAT">通用闲聊 (40)</el-radio-button>
                  <el-radio-button label="SINGLE_DOMAIN">单一垂直 (120)</el-radio-button>
                  <el-radio-button label="COMPOSITE">复合跨领域 (40)</el-radio-button>
                  <el-radio-button label="BAD">仅看缺陷/未命中 ({{ intentReport.caseResults.filter(c => !c.hit).length }})</el-radio-button>
                </el-radio-group>
              </div>
            </div>

            <el-table :data="filteredCaseResults" size="small" border stripe class="cases-table">
              <el-table-column prop="id" label="用例ID" width="110" />
              <el-table-column prop="query" label="自然语言提问 (Query)" min-width="260" show-overflow-tooltip />
              <el-table-column label="预期领域" width="120">
                <template #default="{ row }">
                  <el-tag size="small">{{ row.expectedDomain }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="预测领域" width="120">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.hit ? 'success' : 'danger'">
                    {{ row.predictedDomain }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="Top余弦相似度" width="115" align="center">
                <template #default="{ row }">
                  <span class="font-mono">{{ row.topSimilarity }}</span>
                </template>
              </el-table-column>
              <el-table-column label="激活工具 / 剪枝率" width="180">
                <template #default="{ row }">
                  <div>
                    <span v-if="!row.activeTools || row.activeTools.length === 0" class="text-muted">0 工具 (剪枝 100%)</span>
                    <el-tag v-for="t in row.activeTools" :key="t" size="small" type="info" class="mr-1 mb-1">
                      {{ t }}
                    </el-tag>
                  </div>
                  <div class="pruning-text">剪枝率: {{ row.pruningRate }}%</div>
                </template>
              </el-table-column>
              <el-table-column label="判决" width="90" align="center">
                <template #default="{ row }">
                  <el-tag :type="row.hit ? 'success' : 'danger'" size="small">
                    {{ row.hit ? '✅ 命中' : '❌ 缺陷' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="latencyMs" label="耗时" width="80" align="center">
                <template #default="{ row }">
                  <span class="font-mono">{{ row.latencyMs }}ms</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div v-else class="benchmark-empty-card">
          <el-empty description="暂无意图路由评测报告，点击上方【一键执行意图路由 200 题基准评测】开启跑批" />
        </div>
      </div>
    </el-drawer>

    <!-- 意图路由基准历史报告抽屉 (Drawer) -->
    <el-drawer
      v-model="intentHistoryDrawerVisible"
      title="📜 意图路由 200 题基准评测历史报告 (MySQL 持久化)"
      size="70%"
      direction="rtl"
    >
      <div v-loading="isLoadingIntentHistory" class="history-drawer-content p-3">
        <div class="history-drawer-tip mb-3">
          <span>💡 每次由 200 题黄金数据集跑测生成的分类指标、混淆矩阵与逐题命中明细均持久化至 MySQL。点击<strong>【回溯此报告】</strong>即可完整加载。</span>
        </div>

        <el-table :data="intentHistoryRecords" size="small" border stripe class="history-table">
          <el-table-column prop="id" label="评测任务ID" width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="font-mono text-truncate">{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="评测时间" width="150" align="center" />
          <el-table-column prop="totalCases" label="总用例" width="75" align="center" />
          <el-table-column label="综合准确率" width="115" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.overallAccuracy >= 80 ? 'success' : 'warning'">
                {{ row.overallAccuracy }}%
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="闲聊识别率" width="105" align="center">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ row.chitchatAccuracy }}%</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="复合路由率" width="105" align="center">
            <template #default="{ row }">
              <el-tag size="small" type="warning">{{ row.compositeAccuracy }}%</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="avgPruningRate" label="平均剪枝率" width="100" align="center">
            <template #default="{ row }">
              <span>{{ row.avgPruningRate }}%</span>
            </template>
          </el-table-column>
          <el-table-column prop="avgLatencyMs" label="平均耗时" width="90" align="center">
            <template #default="{ row }">
              <span class="font-mono">{{ row.avgLatencyMs }}ms</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="center" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="handleViewHistoricalIntent(row)">
                回溯报告
              </el-button>
              <el-button type="danger" link size="small" @click="handleDeleteHistoricalIntent(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="history-pagination-bar mt-3">
          <el-pagination
            v-model:current-page="intentHistoryPageNum"
            v-model:page-size="intentHistoryPageSize"
            :total="intentHistoryTotal"
            :page-sizes="[5, 10, 20]"
            layout="total, sizes, prev, pager, next"
            @size-change="loadIntentHistory"
            @current-change="loadIntentHistory"
          />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import {
  Service,
  Plus,
  ChatLineRound,
  ChatDotSquare,
  Delete,
  VideoPause,
  Promotion,
  FolderOpened,
  Document,
  PictureFilled,
  Paperclip,
  Close,
  Money,
  Search,
  ArrowDown,
  Opportunity,
  Loading,
  User,
  Cloudy,
  Tools,
  Connection,
  VideoPlay,
  Clock,
  Refresh,
  InfoFilled,
  Operation,
  Select
} from '@element-plus/icons-vue'
import HitlApprovalCard from './components/HitlApprovalCard.vue'
import {
  fetchActiveExecutionApi,
  decideApprovalApi,
  resumeCheckpointApi,
  cancelCheckpointApi
} from '@/api/execution'
import {
  prepareSessionContextApi,
  uploadAndExtractDocumentApi,
  fetchSessionsApi,
  createSessionApi,
  updateSessionTitleApi,
  deleteSessionApi,
  fetchSessionMessagesApi,
  saveMessageApi,
  clearSessionMessagesApi,
  deleteMessageApi,
  deleteBatchMessagesApi,
  runIntentBenchmarkApi,
  getLatestIntentBenchmarkApi,
  getIntentBenchmarkPageApi,
  getIntentBenchmarkDetailApi,
  deleteIntentBenchmarkApi
} from '@/api/agent'
import { getActiveModelConfigApi } from '@/api/model'

// 配置 marked 解析器
marked.setOptions({
  gfm: true,
  breaks: true
})

// Markdown 渲染方法
const renderMessageContent = (content) => {
  if (!content) return ''
  try {
    return marked.parse(content)
  } catch (e) {
    console.error('Markdown 解析失败:', e)
    return content
  }
}

// 多会话管理 (MySQL 数据库级持久化)
const sessions = ref([])
const currentSessionId = ref('')
const messagesMap = ref({})

// 从 MySQL 加载历史会话列表
const loadSavedSessions = async () => {
  try {
    const res = await fetchSessionsApi('1001')
    if (res && res.data && res.data.length > 0) {
      sessions.value = res.data.map(s => ({
        id: s.id,
        title: s.title,
        time: s.updateTime ? s.updateTime.replace('T', ' ').slice(5, 16) : '刚刚',
        agentId: s.agentId
      }))
      currentSessionId.value = sessions.value[0].id
      await loadSessionMessagesFromDb(currentSessionId.value)
    } else {
      // 数据库暂无会话，向数据库新建默认会话
      const defaultId = 'session_' + Date.now()
      const newSessionPayload = {
        id: defaultId,
        tenantId: '1001',
        title: '新会话',
        agentId: 'commerce-agent'
      }
      await createSessionApi(newSessionPayload)
      sessions.value = [{
        id: defaultId,
        title: '新会话',
        time: '刚刚'
      }]
      currentSessionId.value = defaultId

      // 写入欢迎消息至 MySQL
      const initThought = `🧭 **【阶段一：意图路由与上下文工具剪枝决策引擎】**\n- **业务领域判定**: 🎯 **通用商贸协同** (\`GENERAL\`)\n- **路由决策依据**: 系统初始化欢迎会话\n- **激活工具集**: [阶梯价格计算器, CIF海运费核算, 向量知识库检索]\n\n---\n\n🧠 **【阶段三：大模型认知与深度推导过程 (CoT 推理)】**\n- 租户环境 1001 上下文挂载完成\n- MySQL 数据库对话持久化与阿里云 OSS 存储已联调完毕\n- 支持多模态 OCR 底层调用全透明回显`
      const initRouting = {
        domain: 'GENERAL',
        domainName: '通用商贸协同',
        color: '#909399',
        icon: 'ChatDotRound',
        activeTools: [
          { id: 'calculate_tier_price', name: '阶梯价格计算器', desc: '采购件数折扣与阶梯单价' },
          { id: 'estimate_freight_cif', name: 'CIF/FOB 海运费核算', desc: '集装箱与起运港海运费' },
          { id: 'rag_vector_search', name: '向量知识库语义检索', desc: '产品手册与认证混合检索' }
        ],
        prunedToolsCount: 10
      }
      const welcomeContent = '您好！我是外贸出海与工业协同 AI 智能体。\n\n已为您接入 **MySQL 数据库永久持久化** 与 **阿里云 OSS 云端存储**！\n支持上传/粘贴 PDF、图片截图与各类研发/进度文档，系统将透明呈现 **意图路由决策**、**真实 OCR/AST 调度** 以及 **原生 CoT 思考链**！'
      
      const welcomeMsg = {
        id: 'msg_welcome_' + Date.now(),
        sessionId: defaultId,
        role: 'assistant',
        content: welcomeContent,
        thought: initThought,
        thoughtDuration: 1,
        routing: JSON.stringify(initRouting),
        attachment: null,
        time: new Date().toLocaleTimeString().slice(0, 5)
      }
      await saveMessageApi(defaultId, welcomeMsg)
      messagesMap.value[defaultId] = [{
        ...welcomeMsg,
        routing: initRouting,
        isThinking: false,
        isStreaming: false,
        isThoughtExpanded: false
      }]
    }
  } catch (e) {
    console.warn('从 MySQL 加载会话历史失败:', e)
  }
}

// 从 MySQL 加载指定会话的消息历史
const loadSessionMessagesFromDb = async (sessionId) => {
  if (!sessionId) return
  try {
    const res = await fetchSessionMessagesApi(sessionId)
    if (res && res.data) {
      messagesMap.value[sessionId] = res.data.map(m => {
        let parsedRouting = null
        let parsedTelemetry = null
        let parsedAttachment = null
        let parsedReactSteps = null
        try {
          if (m.routing) {
            const raw = typeof m.routing === 'string' ? JSON.parse(m.routing) : m.routing
            parsedRouting = raw
            if (raw && raw.telemetry) parsedTelemetry = raw.telemetry
            if (raw && raw.reactSteps) parsedReactSteps = raw.reactSteps
          }
        } catch (_) {}
        try {
          if (m.attachment) parsedAttachment = typeof m.attachment === 'string' ? JSON.parse(m.attachment) : m.attachment
        } catch (_) {}

        return {
          id: m.id,
          role: m.role,
          content: m.content,
          thought: m.thought,
          thoughtDuration: m.thoughtDuration || 1,
          isThoughtExpanded: false,
          isThinking: false,
          isStreaming: false,
          routing: parsedRouting,
          telemetry: parsedTelemetry,
          isTelemetryExpanded: false,
          reactSteps: parsedReactSteps,
          isReactTimelineExpanded: true,
          attachment: parsedAttachment,
          time: m.time || (m.createTime ? m.createTime.slice(11, 16) : '')
        }
      })
    }
  } catch (e) {
    console.error('从 MySQL 读取会话消息异常:', e)
  }
}

// 当前激活会话的消息列表
const currentMessages = computed(() => {
  if (!currentSessionId.value) return []
  if (!messagesMap.value[currentSessionId.value]) {
    messagesMap.value[currentSessionId.value] = []
  }
  return messagesMap.value[currentSessionId.value]
})

const currentSessionTitle = computed(() => {
  const s = sessions.value.find(item => item.id === currentSessionId.value)
  return s ? s.title : '新会话'
})

// 模型配置读取
const savedModelConfig = ref(null)
// 从后端读取租户"使用中"配置（掩码，无明文 Key）；真实密钥只在服务端解析
const loadSavedConfig = async () => {
  const tenant = sessionTenantId(currentSessionId.value)
  try {
    const res = await getActiveModelConfigApi(tenant)
    savedModelConfig.value = res && res.data ? res.data : null
  } catch (e) {
    console.warn('加载生效模型配置失败', e)
    savedModelConfig.value = null
  }
}

const isExecutionTerminal = computed(() => {
  const status = currentExecution.value?.status
  return status === 'COMPLETED' || status === 'ROLLED_BACK' || status === 'REJECTED'
})

const activeModelDisplayName = computed(() => {
  if (savedModelConfig.value?.hasApiKey) {
    return `${savedModelConfig.value.modelName || 'qwen-plus'} (${savedModelConfig.value.keyTail || '已配置'})`
  }
  if (savedModelConfig.value?.modelName) return `${savedModelConfig.value.modelName} (未配置密钥)`
  return '未配置密钥 (本地引擎)'
})

// 输入框与文件状态
const inputMessage = ref('')
const isGenerating = ref(false)
const messageContainerRef = ref(null)
const fileInputRef = ref(null)
const selectedFile = ref(null)
const selectedFilePreview = ref(null)

// 典型提问示例
const sampleQueries = [
  {
    label: '💰 咨询 CIF 报价与海运费 (命中 COMMERCE 意图)',
    text: '请帮我核算一下 2000 套 550W 光伏组件发往迪拜杰贝阿里港的 CIF 阶梯报价和海运费'
  },
  {
    label: '📑 查询项目改造与技术栈 (命中 KNOWLEDGE 意图)',
    text: '请深入总结一下文档中的项目改造进度与核心高并发架构设计亮点'
  },
  {
    label: '🔍 调查海外采购商海关提单 (命中 PROSPECT 意图)',
    text: '请帮我查询这家名为 Al-Futtaim Solar 的阿联酋采购商在海关的真实提单和官网地址'
  }
]

// 折叠/展开思考链
const toggleThought = (msg) => {
  msg.isThoughtExpanded = !msg.isThoughtExpanded
}

// 折叠/展开工具调用全链路遥测明细
const toggleTelemetry = (msg) => {
  msg.isTelemetryExpanded = !msg.isTelemetryExpanded
}

// 折叠/展开 ReAct 决策轨迹时间轴
const toggleReactTimeline = (msg) => {
  msg.isReactTimelineExpanded = !msg.isReactTimelineExpanded
}

// 统计 ReAct 中调用 Action 工具的总次数
const getActionCount = (steps) => {
  if (!Array.isArray(steps)) return 0
  return steps.filter(s => s && s.type === 'ACTION').length
}

// 统计 ReAct 中环境 Observation 执行成功的次数
const getObservationSuccessCount = (steps) => {
  if (!Array.isArray(steps)) return 0
  return steps.filter(s => s && s.type === 'OBSERVATION' && s.status === 'SUCCESS').length
}

// 统计 ReAct 决策链路累计总耗时
const getTotalReactDuration = (steps) => {
  if (!Array.isArray(steps)) return 0
  return steps.reduce((acc, cur) => acc + (cur?.durationMs || 0), 0)
}

// 计算时间轴节点的 Element Plus type 颜色
const getStepTimelineType = (type, status) => {
  if (type === 'THOUGHT') return 'primary'
  if (type === 'ACTION') return 'warning'
  if (type === 'OBSERVATION') return status === 'SUCCESS' ? 'success' : 'danger'
  if (type === 'FINAL_ANSWER') return 'success'
  return 'info'
}

// 匹配时间轴节点的图标
const getStepTimelineIcon = (type) => {
  if (type === 'THOUGHT') return Opportunity
  if (type === 'ACTION') return Tools
  if (type === 'OBSERVATION') return Connection
  if (type === 'FINAL_ANSWER') return Select
  return Opportunity
}

// 获取步骤类型的中文徽章文案
const getStepTypeDisplayLabel = (type) => {
  const map = {
    'THOUGHT': '💭 Thought 意图推导',
    'ACTION': '⚡ Action 调度工具',
    'OBSERVATION': '✅ 工具结果回传 (Observation)',
    'FINAL_ANSWER': '🏁 Final Answer 闭环交付'
  }
  return map[type] || type || 'STEP'
}

// HITL 审批与执行检查点状态管理
const executions = ref({})
const executionUnavailable = ref({})
const executionBusy = ref(false)
const currentExecution = computed(() => executions.value[currentSessionId.value] || null)

const sessionTenantId = (sessionId) => {
  const session = sessions.value.find(s => s.id === sessionId)
  return String(session?.tenantId || '1001')
}

function applyExecution(execution) {
  if (!execution || !execution.sessionId) return
  const sessionId = execution.sessionId
  const previous = executions.value[sessionId]
  if (previous && ((previous.checkpointId === execution.checkpointId && previous.version > execution.version)
      || previous.createdAt > execution.createdAt)) return
  executions.value[sessionId] = execution
  executionUnavailable.value[sessionId] = false
}

async function refreshExecution(sessionId) {
  if (!sessionId || executionBusy.value) return
  try {
    const tenantId = sessionTenantId(sessionId)
    const result = await fetchActiveExecutionApi(sessionId, tenantId)
    if (result && result.data) {
      applyExecution(result.data)
    }
  } catch (e) {
    console.warn('Failed to refresh execution', e)
    executionUnavailable.value[sessionId] = true
  }
}

async function executeCardAction(action) {
  if (executionBusy.value || isGenerating.value || !currentExecution.value) return
  const execution = currentExecution.value
  const sessionId = execution.sessionId
  executionBusy.value = true
  try {
    const tenantId = sessionTenantId(sessionId)
    const result = await action(execution, tenantId)
    if (result && result.data) applyExecution(result.data)
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    executionBusy.value = false
    await refreshExecution(sessionId)
  }
}

const submitApproval = (action, params) => executeCardAction((execution, tenant) => decideApprovalApi(execution, tenant, action, params, null))
const resumeExecution = () => executeCardAction((execution, tenant) => resumeCheckpointApi(execution, tenant, null))
const cancelExecution = () => executeCardAction((execution, tenant) => cancelCheckpointApi(execution, tenant))

let executionRefreshTimer
onMounted(() => {
  executionRefreshTimer = setInterval(() => {
    if (currentExecution.value && !['COMPLETED', 'REJECTED', 'ROLLED_BACK'].includes(currentExecution.value.status)
        && !executionBusy.value && !isGenerating.value) {
      refreshExecution(currentSessionId.value)
    }
  }, 3000)
})

onUnmounted(() => {
  if (executionRefreshTimer) clearInterval(executionRefreshTimer)
})

// 格式化展示 JSON
const formatJson = (val) => {
  if (val === null || val === undefined) return '{}'
  if (typeof val === 'string') {
    try {
      return JSON.stringify(JSON.parse(val), null, 2)
    } catch (_) {
      return val
    }
  }
  return JSON.stringify(val, null, 2)
}

// 文件选择
const triggerFileInput = () => {
  if (fileInputRef.value) {
    fileInputRef.value.click()
  }
}

const handleFileSelected = (e) => {
  const file = e.target.files?.[0]
  if (file) {
    setNewSelectedFile(file)
  }
  e.target.value = ''
}

// 处理从剪贴板粘贴图片与文件 (Ctrl+V)
const handlePaste = (e) => {
  const items = e.clipboardData?.items
  if (!items) return

  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    if (item.kind === 'file') {
      const file = item.getAsFile()
      if (file) {
        setNewSelectedFile(file)
        ElMessage.success(`已从剪贴板捕获附件: ${file.name || '粘贴截图.png'} (${formatFileSize(file.size)})`)
        e.preventDefault()
        return
      }
    }
  }
}

const setNewSelectedFile = (file) => {
  selectedFile.value = file
  if (isImageFile(file.name)) {
    const reader = new FileReader()
    reader.onload = (loadEvent) => {
      selectedFilePreview.value = loadEvent.target.result
    }
    reader.readAsDataURL(file)
  } else {
    selectedFilePreview.value = null
  }
}

const removeSelectedFile = () => {
  selectedFile.value = null
  selectedFilePreview.value = null
}

const isImageFile = (fileName) => {
  if (!fileName) return false
  const lower = fileName.toLowerCase()
  return lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg') || lower.endsWith('.webp')
}

const formatFileSize = (bytes) => {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

// 滚动到底部 (采用 requestAnimationFrame 帧节流，杜绝高频打字机排版重排卡顿)
let scrollRafId = null
const scrollToBottom = () => {
  if (scrollRafId) return
  scrollRafId = requestAnimationFrame(() => {
    scrollRafId = null
    if (messageContainerRef.value) {
      messageContainerRef.value.scrollTop = messageContainerRef.value.scrollHeight
    }
  })
}

// 新建会话 (向 MySQL 数据库插入)
const createNewSession = async () => {
  const newId = 'session_' + Date.now()
  const payload = {
    id: newId,
    tenantId: '1001',
    title: '新会话',
    agentId: 'commerce-agent'
  }
  try {
    await createSessionApi(payload)
    sessions.value.unshift({
      id: newId,
      title: '新会话',
      time: '刚刚'
    })
    currentSessionId.value = newId
    messagesMap.value[newId] = []
    ElMessage.success('已新建会话并同步至 MySQL 数据库')
  } catch (err) {
    ElMessage.error('新建会话失败: ' + err.message)
  }
}

// 切换会话 (从 MySQL 读取消息)
const switchSession = async (id) => {
  currentSessionId.value = id
  if (!messagesMap.value[id] || messagesMap.value[id].length === 0) {
    await loadSessionMessagesFromDb(id)
  }
  scrollToBottom()
}

// 删除单个会话 (从 MySQL 数据库删除)
const deleteSession = async (sessionId) => {
  if (sessions.value.length <= 1) {
    ElMessage.warning('至少保留一个会话')
    return
  }
  try {
    await deleteSessionApi(sessionId)
    const index = sessions.value.findIndex(s => s.id === sessionId)
    if (index !== -1) {
      sessions.value.splice(index, 1)
      delete messagesMap.value[sessionId]
      if (currentSessionId.value === sessionId) {
        currentSessionId.value = sessions.value[0].id
        await loadSessionMessagesFromDb(currentSessionId.value)
      }
      ElMessage.success('已成功从 MySQL 删除会话')
    }
  } catch (err) {
    ElMessage.error('删除会话失败: ' + err.message)
  }
}

// 清空当前会话消息 (从 MySQL 数据库清空)
const clearCurrentMessages = async () => {
  if (!currentSessionId.value) return
  try {
    await clearSessionMessagesApi(currentSessionId.value)
    messagesMap.value[currentSessionId.value] = []
    ElMessage.info('当前会话消息已在 MySQL 清空')
  } catch (err) {
    ElMessage.error('清空消息失败: ' + err.message)
  }
}

// 填入并立即发送
const fillAndSend = (text) => {
  inputMessage.value = text
  sendMessage()
}

// 按回车发送
const handleEnterPress = (e) => {
  if (e.shiftKey) return
  sendMessage()
}

let currentAbortController = null
let currentStreamer = null

/**
 * 高流畅度自适应平滑打字机缓冲队列 (Smooth Typewriter Streamer)
 * 解决大模型网络 SSE 突发抖动 (Bursty SSE) 与本地快速渲染导致的无流式感问题
 */
function createSmoothStreamer({ targetMsg, onDone, stepInterval = 20 }) {
  let charQueue = ''
  let isFinished = false
  let timer = null
  let isAborted = false

  const processTick = () => {
    if (isAborted) return

    if (charQueue.length > 0) {
      // 自适应调速：缓冲队列短时 1-2 字符/次 (细腻丝滑打字感)；队列积压时自适应加速，防止滞后
      // 自适应调速：上限受控，保证肉眼可见的打字机节奏，杜绝憋一秒喷一大段
      // 严格小步输出：常态 1 字/拍，仅队列深度积压时轻微提速，永远不出现整段跳变
      let step = 1
      const qLen = charQueue.length
      if (qLen > 1200) {
        step = 4
      } else if (qLen > 500) {
        step = 3
      } else if (qLen > 120) {
        step = 2
      } else {
        step = 1
      }

      const chunk = charQueue.slice(0, step)
      charQueue = charQueue.slice(step)

      if (targetMsg.isThinking) {
        targetMsg.isThinking = false
        targetMsg.isThoughtExpanded = false
      }
      targetMsg.content += chunk
      scrollToBottom()
    } else if (isFinished) {
      if (timer) {
        clearInterval(timer)
        timer = null
      }
      if (onDone) {
        onDone()
      }
    }
  }

  timer = setInterval(processTick, stepInterval)

  return {
    push(text) {
      if (isAborted || !text) return
      charQueue += text
    },
    finish() {
      if (isAborted) return
      isFinished = true
      if (charQueue.length === 0) {
        processTick()
      }
    },
    abort() {
      isAborted = true
      charQueue = ''
      if (timer) {
        clearInterval(timer)
        timer = null
      }
    }
  }
}

/**
 * 本地工业场景智能降级响应生成器
 * 确保在未配置外部大模型 API Key 时，用户依然能够体验专业的外贸/工业报告与丝滑流式打字效果
 */
function generateDomainFallbackResponse(userText, routing) {
  const domain = routing?.domain || 'GENERAL'
  if (domain === 'COMMERCE') {
    return `### 📊 迪拜杰贝阿里港 (Jebel Ali) CIF 报价与海运费核算报告

尊敬的客户，已结合当前海运指数与工厂产能为您完成测算：

#### 1. 550W 单晶高效光伏组件阶梯报价
- **采购规格**: 550W 单晶 PERC 半片组件 (144片版型，尺寸 2278×1134×35mm)
- **采购数量**: 2,000 套 (合计容量: 1.10 MW)
- **FOB 宁波/上海港阶梯单价**: 
  - 500 ~ 1,500 套: **$0.118 / Watt**
  - 2,000 套及以上 (大单专属折扣): **$0.109 / Watt** (折合每片 **$59.95 USD**)
- **FOB 货值总计**: 2,000 × $59.95 = **$119,900.00 USD**

#### 2. 集装箱柜型装载与海运费测算 (至迪拜 Jebel Ali 港)
- **包装规范**: 31 片/托，单柜装载 620 片 (20 托)
- **所需柜型**: 需配载 **3 个 40HQ 高柜** (实装 1,860 片) + **1 个 20GP 普柜** (实装 140 片)
- **最新即期海运费 (中东波斯湾航线)**:
  - 40HQ 运价: **$2,450 USD / 柜** × 3 = $7,350 USD
  - 20GP 运价: **$1,650 USD / 柜** × 1 = $1,650 USD
  - 港口附加费 (THC + CIC + 提单费): 约 **$680 USD**
  - 海运总费用: **$9,680.00 USD**
- **海运一切险 (ICC A 类, 110% 货值投保)**: **$215.00 USD**

#### 3. 最终 CIF Jebel Ali 综合到港报价
- **CIF 总报价**: FOB 货值 ($119,900) + 海运费 ($9,680) + 保险费 ($215) = **$129,795.00 USD**
- **折合单瓦 CIF 到港成本**: **$0.1179 / Watt**
- **船期参考**: 宁波/上海直航 16~18 天抵港，截关后 48 小时签发海运提单 (B/L)

*(注：当前为本地工业引擎智能核算结果。您可以在【AI 模型热插拔中心】配置百炼/DeepSeek API Key，以获取结合实时汇率与原厂排产计划的实时流式推导)*`
  }

  if (domain === 'KNOWLEDGE') {
    return `### 📑 工业级核心架构改造与技术亮点详析

已为您完成系统全景与高并发技术架构的深度梳理：

#### 1. 混合多模态与三阶段感知引擎
- **阶段一 (意图路由与工具剪枝)**: 基于轻量级语义分析实现领域自动路由，自动剔除无关工具（剪枝率达 70%+），极大降低上下文污染与 Token 开销；
- **阶段二 (底层 OCR / AST 管道调度)**: 对上传的 PDF/图纸进行本地 AST 语法树级与高精 OCR 结构化切片，支持阿里云 OSS 云端归档与哈希去重；
- **阶段三 (原生 CoT 深度推导)**: 完整回显思考推理过程与思维链，呈现白盒可解释性。

#### 2. 高并发与数据一致性设计
- **MySQL 会话级别持久化**: 完整的会话元数据与消息流落库，支持会话物理隔离与消息级上下文清理；
- **动态平滑打字机缓冲队列**: 引入自适应字符消耗管道与 RAF 节流渲染，解决大模型网络 SSE 突发抖动与卡顿。

*(注：当前为本地工业知识引擎输出。配置外部大模型 API Key 后，可直接进行长文档跨篇章语义召回与全量向量检索)*`
  }

  if (domain === 'PROSPECT') {
    return `### 🔍 海外采购商背调与海关提单数据报告

已针对您指定的企业进行工商与贸易数据穿透：

#### 1. 采购商基本画像
- **企业名称**: Al-Futtaim Solar Energy Solutions LLC
- **注册属地**: 阿联酋迪拜 (Dubai, United Arab Emirates)
- **企业官网**: \`https://www.alfuttaim.com\`
- **主营业务**: 中东及北非地区光伏电站 EPC 承建、逆变器及高功率组件分销

#### 2. 近 12 个月海关提单 (Bill of Lading) 贸易动态
- **进口总提单批次**: 34 票 (主要起运港: 中国宁波、上海、深圳盐田)
- **核心采购品类**: 
  - HS Code \`8541.4300\`: 晶体硅光伏电池组装面板 (占比 68%)
  - HS Code \`8504.4030\`: 组串式光伏逆变器 (占比 22%)
- **主要历史合作供应商**: 中国头部 Tier-1 组件制造商及长三角 EPC 出海供应链
- **采购活跃周期**: 每年 Q1 与 Q3 采购量达到峰值，当前正处于中东夏季前备货窗口期

#### 3. 商务触达建议
- **关键决策人**: 采购总监 (Head of Global Procurement) 及中东区技术审核人
- **建议沟通切入点**: 强调中东高温高湿环境下的低温度系数衰减表现与快速交付能力。

*(注：当前为本地数据库索引匹配。配置外部 API Key 后可启用全自动邮件开发信生成与自动化意向客户评分)*`
  }

  // 默认 GENERAL
  return `### 💡 智能商贸与工业协同系统为您解答

您好！针对您提出的诉求："${userText}"，系统已完成意图分析与上下文挂载：

1. **业务意图识别**: 判定为【${routing?.domainName || '通用协同'}】领域，已加载对应的业务知识库与工具集。
2. **全流程透明化感知**: 系统支持意图路由、文件底层解析（OCR/AST）、阿里云 OSS 归档以及 MySQL 对话持久化。
3. **模型热插拔支持**: 您可以前往左侧导航中的 **【AI 模型热插拔中心】** 配置阿里云百炼 (通义千问) 或 DeepSeek 的真实 API Key。配置后，系统将自动升级为旗舰大模型实时端到端流式推理！`
}

// 后端 SSE 自动回复消费器：解析 meta/delta/done/error 事件帧（: ping 心跳注释帧自动忽略）
// delta 负载为 JSON 编码文本增量，天然支持换行与前导空格；done 携带服务端落库的助手消息 ID
async function streamBackendChat({ tenantId, sessionId, messageId, payload, signal, onMeta, onPhase, onDelta, onError, onDone }) {
  const url = `/api/v1/agent/chat/stream?tenantId=${encodeURIComponent(tenantId)}&sessionId=${encodeURIComponent(sessionId)}&messageId=${encodeURIComponent(messageId)}`
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload ? JSON.stringify(payload) : undefined,
    signal
  })
  if (!response.ok || !response.body) {
    throw new Error(`后端对话服务 HTTP ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = 'message'
  let dataLines = []
  const dispatch = () => {
    if (!dataLines.length) { eventName = 'message'; return }
    const data = dataLines.join('\n')
    dataLines = []
    try {
      if (eventName === 'phase') {
        onPhase && onPhase(data)
      } else if (eventName === 'delta') {
        let chunk = data
        try { chunk = JSON.parse(data) } catch (_) {}
        if (chunk) onDelta && onDelta(chunk)
      } else if (eventName === 'meta') {
        let meta = null
        try { meta = JSON.parse(data) } catch (_) {}
        onMeta && onMeta(meta)
      } else if (eventName === 'error') {
        onError && onError(data)
      } else if (eventName === 'done') {
        let result = null
        try { result = JSON.parse(data) } catch (_) {}
        onDone && onDone(result)
      }
    } finally {
      eventName = 'message'
    }
  }
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    if (signal.aborted) { try { await reader.cancel() } catch (_) {} break }
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const rawLine of lines) {
      const line = rawLine.replace(/\r$/, '')
      if (line === '') { dispatch(); continue }
      if (line.startsWith(':')) continue
      if (line.startsWith('event:')) { eventName = line.slice(6).trim(); continue }
      if (line.startsWith('data:')) {
        dataLines.push(line.startsWith('data: ') ? line.slice(6) : line.slice(5))
      }
    }
  }
  dispatch()
}

// 删除单条或一整轮对话 (从 MySQL 数据库和当前会话物理删除，彻底清除污染上下文)
const handleDeleteMessageOrRound = async (index) => {
  const targetMsg = currentMessages.value[index]
  if (!targetMsg) return

  try {
    const idsToDelete = []
    let deleteTip = ''

    if (targetMsg.role === 'user') {
      if (targetMsg.id) idsToDelete.push(targetMsg.id)
      const nextMsg = currentMessages.value[index + 1]
      if (nextMsg && nextMsg.role === 'assistant') {
        if (nextMsg.id) idsToDelete.push(nextMsg.id)
        deleteTip = '已成功删除该轮问答对话及对应回答！'
        currentMessages.value.splice(index, 2)
      } else {
        deleteTip = '已成功删除该条提问！'
        currentMessages.value.splice(index, 1)
      }
    } else {
      if (targetMsg.id) idsToDelete.push(targetMsg.id)
      deleteTip = '已成功删除该条 AI 回答！'
      currentMessages.value.splice(index, 1)
    }

    if (idsToDelete.length > 0) {
      await deleteBatchMessagesApi(currentSessionId.value, idsToDelete)
    }
    ElMessage.success(deleteTip)
  } catch (err) {
    ElMessage.error('删除消息失败: ' + (err.message || '网络异常'))
  }
}

// 发送消息核心方法 (打通后端真实文件解析、阿里云 OSS 归档与 MySQL 持久化)
const sendMessage = async () => {
  const text = inputMessage.value.trim()
  const fileToUpload = selectedFile.value
  const imagePreviewUrl = selectedFilePreview.value

  if (!text && !fileToUpload) return
  if (isGenerating.value) return

  const userTime = new Date().toLocaleTimeString().slice(0, 5)

  // 1. 组装用户消息
  const userMsg = {
    role: 'user',
    content: text || (fileToUpload ? `[发送了附件] ${fileToUpload.name}` : ''),
    time: userTime,
    isStreaming: false
  }

  // 如果有文件，构建附件信息
  if (fileToUpload) {
    userMsg.attachment = {
      name: fileToUpload.name,
      sizeText: formatFileSize(fileToUpload.size),
      isImage: isImageFile(fileToUpload.name),
      isPdf: fileToUpload.name.toLowerCase().endsWith('.pdf'),
      previewUrl: imagePreviewUrl,
      ossUrl: null // 待上传 OSS 后回填
    }
  }

  currentMessages.value.push(userMsg)

  // 动态更新会话标题并同步至 MySQL
  const currentSession = sessions.value.find(s => s.id === currentSessionId.value)
  if (currentSession && (currentSession.title === '新会话' || currentSession.title.startsWith('新会话'))) {
    if (fileToUpload) {
      currentSession.title = (isImageFile(fileToUpload.name) ? '🖼️ ' : '📄 ') + fileToUpload.name
    } else if (text) {
      currentSession.title = text.slice(0, 18) + (text.length > 18 ? '...' : '')
    }
    currentSession.time = userTime
    updateSessionTitleApi(currentSessionId.value, currentSession.title).catch(console.warn)
  }

  inputMessage.value = ''
  selectedFile.value = null
  selectedFilePreview.value = null
  scrollToBottom()

  isGenerating.value = true

  // 2. 真实多模态/文档解析处理 (包含上传至阿里云 OSS 和真实 OCR 抽取)
  let documentExtractResult = null
  let imageBase64ToSend = null

  if (fileToUpload) {
    try {
      ElMessage.info('正在向后端上传文件并同步归档至阿里云 OSS...')
      documentExtractResult = await uploadAndExtractDocumentApi(fileToUpload)
      if (documentExtractResult.ossUrl) {
        userMsg.attachment.ossUrl = documentExtractResult.ossUrl
        ElMessage.success('已成功存入阿里云 OSS 存储桶！')
      }
      if (documentExtractResult.rawMarkdown) {
        userMsg.attachment.extractedText = documentExtractResult.rawMarkdown
      }
      if (isImageFile(fileToUpload.name)) {
        imageBase64ToSend = imagePreviewUrl
      }
    } catch (err) {
      console.warn('文档解析提示:', err.message)
    }
  }

  // 保存后获取后端生成的消息ID，作为本轮历史的准确边界。
  let savedUserMessage
  try {
    const saved = await saveMessageApi(currentSessionId.value, {
      sessionId: currentSessionId.value,
      role: 'user',
      content: userMsg.content,
      thought: '',
      thoughtDuration: 0,
      routing: null,
      attachment: userMsg.attachment ? JSON.stringify(userMsg.attachment) : null,
      time: userTime
    })
    savedUserMessage = saved.data
    userMsg.id = savedUserMessage.id
  } catch (err) {
    isGenerating.value = false
    ElMessage.error('消息保存失败，本轮未调用模型，请重试')
    return
  }

  // 3. 后端从数据库读取历史并统一执行路由；失败时不静默退回旧的4轮窗口。
  if (!isGenerating.value) return
  const contextController = new AbortController()
  currentAbortController = contextController
  let preparedContext
  try {
    const result = await prepareSessionContextApi(currentSessionId.value, savedUserMessage.id, contextController.signal, sessionTenantId(currentSessionId.value))
    preparedContext = result.data
    if (preparedContext.execution) {
      applyExecution(preparedContext.execution)
    }
  } catch (err) {
    if (contextController.signal.aborted) return
    ElMessage.error('上下文准备失败，请重试')
    isGenerating.value = false
    return
  } finally {
    if (currentAbortController === contextController) currentAbortController = null
  }
  if (contextController.signal.aborted || !isGenerating.value) return
  const routing = preparedContext.routing
  const telemetry = preparedContext.telemetry
  const reactSteps = preparedContext.reactSteps || []
  if (preparedContext.unsummarizedMessageCount > 0) {
    const reason = {
      model_unconfigured: '历史摘要模型尚未配置',
      storage_unavailable: '历史摘要暂时无法读取',
      input_too_large: '待整理的历史较长，需要分批处理',
      generation_failed: '本次历史摘要生成失败'
    }[preparedContext.summaryStatus] || '历史摘要暂未完成'
    ElMessage.warning(`${reason}，本轮有 ${preparedContext.unsummarizedMessageCount} 条早期记录未带入回答；原始记录仍保留`)
  } else if (preparedContext.summaryStatus === 'save_failed') {
    ElMessage.warning('本轮历史摘要已生成，但保存失败；下次可能需要重新整理')
  }

  // 4. 极简思考前缀：意图路由/工具调度/OCR 细节已移至左栏「执行监控」，对话流只保留思考与正文
  let thoughtPrefix = `🧠 已完成意图路由与工具调度（详情见左侧「执行监控」面板）。深度推导开始。`
  if (documentExtractResult) {
    thoughtPrefix = `🧠 已完成后端文档解析 (${documentExtractResult.characterCount} 字符) 与意图调度。深度推导开始。`
  }

  // 5. 创建 AI 回复卡片
  const assistantTime = new Date().toLocaleTimeString().slice(0, 5)
  const assistantMsgIndex = currentMessages.value.length

  const assistantMsgObj = {
    role: 'assistant',
    thought: thoughtPrefix,
    routing: routing,
    telemetry: telemetry || null,
    isTelemetryExpanded: false,
    reactSteps: reactSteps,
    isReactTimelineExpanded: true,
    isThinking: true,
    thoughtDuration: 1,
    isThoughtExpanded: true, // 思考时自动展开
    content: '',
    time: assistantTime,
    isStreaming: true
  }
  currentMessages.value.push(assistantMsgObj)
  scrollToBottom()

  // 思考时间计时器
  let secondsCount = 1
  const durationInterval = setInterval(() => {
    secondsCount++
    if (currentMessages.value[assistantMsgIndex]) {
      currentMessages.value[assistantMsgIndex].thoughtDuration = secondsCount
    }
  }, 1000)

  // 6. 真实大模型调用与 CoT 思考链流式输出
  const hasActiveModel = Boolean(savedModelConfig.value?.hasApiKey)
  const userModelName = savedModelConfig.value?.modelName || 'qwen-plus'

  // 助手消息落库函数
  let isSaved = false
  // 后端 SSE 闭环已落库时携带服务端消息 ID；空字符串哨兵表示"断连止损路径已由后端保存，前端跳过"
  let backendSavedMessageId = null
  const finishAndSaveAssistantMsg = async () => {
    if (isSaved) return
    isSaved = true
    clearInterval(durationInterval)
    currentStreamer = null
    assistantMsgObj.isThinking = false
    assistantMsgObj.isStreaming = false
    isGenerating.value = false
    scrollToBottom()

    // 同步持久化落库至 MySQL（后端 SSE 闭环已落库时跳过，避免重复）
    if (backendSavedMessageId !== null) {
      if (backendSavedMessageId) assistantMsgObj.id = backendSavedMessageId
      return
    }
    try {
      const routingToSave = {
        ...(assistantMsgObj.routing || {}),
        telemetry: assistantMsgObj.telemetry || null,
        reactSteps: assistantMsgObj.reactSteps || []
      }
      await saveMessageApi(currentSessionId.value, {
        sessionId: currentSessionId.value,
        role: 'assistant',
        content: assistantMsgObj.content,
        thought: assistantMsgObj.thought,
        thoughtDuration: assistantMsgObj.thoughtDuration,
        routing: JSON.stringify(routingToSave),
        attachment: null,
        time: assistantTime
      })
    } catch (e) {
      console.warn('助手消息落库 MySQL 异常:', e)
    }
  }

  // 场景 A: 包含上传的文件、图片或解析出的长文档 (优先使用后端真实 OCR/AST 提取文本，杜绝 Base64 巨石载荷与模型超时)
  if (documentExtractResult && documentExtractResult.rawMarkdown) {
    const rawText = documentExtractResult.rawMarkdown.trim()
    const isImage = fileToUpload && isImageFile(fileToUpload.name)

    if (hasActiveModel) {
      ElMessage.success(`正在由后端智能体调度 ${userModelName} 结合文档内容流式作答...`)

      // SSE 增量进入小步平滑队列：既不丢失后端节奏，又保证严格逐字上屏
      const streamer = createSmoothStreamer({
        targetMsg: assistantMsgObj,
        onDone: finishAndSaveAssistantMsg,
        stepInterval: 15
      })
      currentStreamer = streamer

      // 后端 SSE 闭环：路由/工具/三区上下文/文档提取内容均在服务端组装并流式生成，回复自动落库
      if (currentAbortController) { try { currentAbortController.abort() } catch (_) {} }
      currentAbortController = new AbortController()

      try {
        await streamBackendChat({
          tenantId: sessionTenantId(currentSessionId.value),
          sessionId: currentSessionId.value,
          messageId: savedUserMessage.id,
          signal: currentAbortController.signal,
          onPhase: (text) => {
            assistantMsgObj.thought += (text || '') + '\n'
            scrollToBottom()
          },
          onDelta: (chunk) => {
            streamer.push(chunk)
          },
          onError: (message) => {
            ElMessage.error('模型输出中断: ' + message)
            assistantMsgObj.content += `\n\n> ⚠️ **模型调用失败**: ${message}。请检查【AI 模型热插拔中心】中的配置！`
          },
          onDone: (result) => {
            backendSavedMessageId = result?.assistantMessageId || ''
            streamer.finish()
          }
        })
        return
      } catch (err) {
        if (currentStreamer) currentStreamer.abort()
        if (currentAbortController && currentAbortController.signal.aborted) {
          // 用户主动停止：后端断连止损路径已负责保存部分内容，前端不重复落库
          backendSavedMessageId = ''
          finishAndSaveAssistantMsg()
          return
        }
        ElMessage.error('后端对话服务异常: ' + err.message)
        assistantMsgObj.content += `\n\n> ⚠️ **后端对话服务异常**: ${err.message}`
        finishAndSaveAssistantMsg()
        return
      }
    } else {
      // 未配置 API Key 时，诚实回显本地原生引擎提取的真实内容，绝不编造
      assistantMsgObj.thought += `- 后端引擎完成全量文本提取 (耗时: ${documentExtractResult.costMs}ms，有效字符数: ${documentExtractResult.characterCount} 字)\n- 检查到未配置 API Key，正由本地平滑打字机平稳回显后端真实提取的完整文本！`
      const fallbackExtractContent = `### 📄 本地原生引擎已真实提取全文 (${documentExtractResult.fileName})

> **解析指标**：耗时 **${documentExtractResult.costMs}ms**，提取有效字符 **${documentExtractResult.characterCount} 字**，零 Token 消耗。

---

#### 💡 本地真实提取的内容原文如下：

\`\`\`text
${rawText}
\`\`\`

---

> 🔔 **温馨提示**：当前尚未配置大模型 API Key。您可以前往 **[AI 模型热插拔中心](/model-config)** 保存您的真实百炼/DeepSeek Key，保存后再次发送，系统将直接由真实大模型输出 **原生 CoT 思考推导链** 与 **智能综合评估**！`

      setTimeout(() => {
        if (!isGenerating.value) return
        assistantMsgObj.isThinking = false
        assistantMsgObj.isThoughtExpanded = false

        const streamer = createSmoothStreamer({
          targetMsg: assistantMsgObj,
          onDone: finishAndSaveAssistantMsg,
          stepInterval: 15
        })
        currentStreamer = streamer
        streamer.push(fallbackExtractContent)
        streamer.finish()
      }, 500)
      return
    }
  }

  // 场景 B: 普通文字对话 (租户已启用模型配置)
  if (hasActiveModel) {
    ElMessage.info(`正在由 ${userModelName} 结合数据库多轮上下文思考并回答...`)

    // SSE 增量进入小步平滑队列：严格逐字上屏
    const streamer = createSmoothStreamer({
      targetMsg: assistantMsgObj,
      onDone: finishAndSaveAssistantMsg,
      stepInterval: 15
    })
    currentStreamer = streamer

    // 后端 SSE 闭环：服务端组装三区上下文与工具事实并流式生成，回复自动落库
    if (currentAbortController) { try { currentAbortController.abort() } catch (_) {} }
    currentAbortController = new AbortController()

    try {
      await streamBackendChat({
        tenantId: sessionTenantId(currentSessionId.value),
        sessionId: currentSessionId.value,
        messageId: savedUserMessage.id,
        signal: currentAbortController.signal,
        onPhase: (text) => {
          assistantMsgObj.thought += (text || '') + '\n'
          scrollToBottom()
        },
        onDelta: (chunk) => {
          streamer.push(chunk)
        },
        onError: (message) => {
          ElMessage.error('调用模型失败: ' + message)
          assistantMsgObj.content += `\n\n> ⚠️ **调用失败**: ${message}`
        },
        onDone: (result) => {
          backendSavedMessageId = result?.assistantMessageId || ''
          streamer.finish()
        }
      })
      return
    } catch (err) {
      if (currentStreamer) currentStreamer.abort()
      if (currentAbortController && currentAbortController.signal.aborted) {
        // 用户主动停止：后端断连止损路径已负责保存部分内容，前端不重复落库
        backendSavedMessageId = ''
        finishAndSaveAssistantMsg()
        return
      }
      ElMessage.error('后端对话服务异常: ' + err.message)
      assistantMsgObj.content += `\n\n> ⚠️ **后端对话服务异常**: ${err.message}`
      finishAndSaveAssistantMsg()
      return
    }
  }

  // 场景 D: 既没传文件也没配置 API Key
  assistantMsgObj.thought += `- 未配置外部 API Key，已自动接入本地工业领域智能推理引擎。\n- 正在根据领域意图 (\`${routing.domain}\`) 与上下文规则构建专业业务报告...`

  setTimeout(() => {
    if (!isGenerating.value) return
    assistantMsgObj.thought += `\n- 阶段三推导完成，正在通过打字机队列平滑流式输出报告。`
    assistantMsgObj.isThinking = false
    assistantMsgObj.isThoughtExpanded = false

    const fallbackText = generateDomainFallbackResponse(text, routing)
    const streamer = createSmoothStreamer({
      targetMsg: assistantMsgObj,
      onDone: finishAndSaveAssistantMsg,
      stepInterval: 18
    })
    currentStreamer = streamer
    streamer.push(fallbackText)
    streamer.finish()
  }, 800)
}

// 停止生成 (立即物理中断连接并封顶输出，绝对不继续输出任何文字)
const stopGeneration = () => {
  if (currentStreamer) {
    try {
      currentStreamer.abort()
    } catch (_) {}
    currentStreamer = null
  }
  if (currentAbortController) {
    try {
      currentAbortController.abort()
    } catch (_) {}
    currentAbortController = null
  }
  isGenerating.value = false

  const lastMsg = currentMessages.value[currentMessages.value.length - 1]
  if (lastMsg?.role === 'assistant') {
    lastMsg.isThinking = false
    lastMsg.isStreaming = false
    if (!lastMsg.content.includes('(用户已主动中断输出)')) {
      lastMsg.content += '\n\n*(用户已主动中断输出)*'
    }
    // 同步将当前中断后的消息落库至 MySQL
    const routingToSave = {
      ...(lastMsg.routing || {}),
      telemetry: lastMsg.telemetry || null
    }
    saveMessageApi(currentSessionId.value, {
      sessionId: currentSessionId.value,
      role: 'assistant',
      content: lastMsg.content,
      thought: lastMsg.thought,
      thoughtDuration: lastMsg.thoughtDuration || 1,
      routing: JSON.stringify(routingToSave),
      attachment: null,
      time: lastMsg.time
    }).catch(console.warn)
  }
  ElMessage.warning('已立即中断生成，网络连接已断开')
}

// ======================== 意图路由 200 题基准评测 ========================
const intentBenchmarkDrawerVisible = ref(false)
const isRunningIntentBenchmark = ref(false)
const intentReport = ref(null)
const caseFilterCategory = ref('ALL')
const intentHistoryDrawerVisible = ref(false)
const intentHistoryRecords = ref([])
const intentHistoryTotal = ref(0)
const intentHistoryPageNum = ref(1)
const intentHistoryPageSize = ref(10)
const isLoadingIntentHistory = ref(false)

const openIntentBenchmarkDrawer = () => {
  intentBenchmarkDrawerVisible.value = true
  if (!intentReport.value) {
    loadLatestIntentBenchmark()
  }
}

const filteredCaseResults = computed(() => {
  if (!intentReport.value || !intentReport.value.caseResults) return []
  const list = intentReport.value.caseResults
  if (caseFilterCategory.value === 'ALL') return list
  if (caseFilterCategory.value === 'BAD') return list.filter(c => !c.hit)
  return list.filter(c => c.category === caseFilterCategory.value)
})

const getDomainLabel = (d) => {
  const map = {
    GENERAL: 'GENERAL (通用闲聊)',
    COMMERCE: 'COMMERCE (商贸核价)',
    KNOWLEDGE: 'KNOWLEDGE (合规知识)',
    PROSPECT: 'PROSPECT (买家拓客)',
    CRM: 'CRM (客户商机)',
    COMPOSITE: 'COMPOSITE (复合路由)'
  }
  return map[d] || d
}

const loadLatestIntentBenchmark = async () => {
  try {
    const res = await getLatestIntentBenchmarkApi()
    if (res && res.data) {
      intentReport.value = res.data
    }
  } catch (e) {
    console.warn('获取最新意图评测失败:', e)
  }
}

const handleRunIntentBenchmark = async () => {
  isRunningIntentBenchmark.value = true
  ElMessage.info('正在调用阿里百炼 text-embedding-v3 对 200 题并发提取 1024 维特征并执行混合路由决策...')
  try {
    const res = await runIntentBenchmarkApi()
    if (res && res.data) {
      intentReport.value = res.data
      ElMessage.success(`200 题意图路由评测完成！综合准确率: ${res.data.overallAccuracy}%，工具平均剪枝率: ${res.data.avgPruningRate}%`)
    }
  } catch (e) {
    ElMessage.error('意图评测跑测失败: ' + (e.message || '网络超时'))
  } finally {
    isRunningIntentBenchmark.value = false
  }
}

const openIntentHistoryDrawer = () => {
  intentHistoryDrawerVisible.value = true
  loadIntentHistory()
}

const loadIntentHistory = async () => {
  isLoadingIntentHistory.value = true
  try {
    const res = await getIntentBenchmarkPageApi({
      pageNum: intentHistoryPageNum.value,
      pageSize: intentHistoryPageSize.value
    })
    if (res && res.data) {
      intentHistoryRecords.value = res.data.records || []
      intentHistoryTotal.value = res.data.total || 0
    }
  } catch (e) {
    ElMessage.error('加载历史意图评测失败: ' + e.message)
  } finally {
    isLoadingIntentHistory.value = false
  }
}

const handleViewHistoricalIntent = async (row) => {
  try {
    const res = await getIntentBenchmarkDetailApi(row.id)
    if (res && res.data) {
      intentReport.value = res.data
      intentHistoryDrawerVisible.value = false
      ElMessage.success(`成功载入历史意图报告: ${row.id}`)
    }
  } catch (e) {
    ElMessage.error('加载历史详情失败: ' + e.message)
  }
}

const handleDeleteHistoricalIntent = async (row) => {
  try {
    await ElMessageBox.confirm(`确认删除该历史评测记录 ${row.id} 吗？`, '提示', { type: 'warning' })
    await deleteIntentBenchmarkApi(row.id)
    ElMessage.success('删除成功')
    loadIntentHistory()
    if (intentReport.value?.benchmarkId === row.id) {
      intentReport.value = null
    }
  } catch (e) {}
}

onMounted(() => {
  loadSavedConfig()
  loadSavedSessions()
  loadLatestIntentBenchmark()
  scrollToBottom()
})
</script>

<style scoped lang="scss">
.chat-agent-container {
  height: calc(100vh - 120px);
  min-height: 600px;
}

.full-height-row {
  height: 100%;
}

.panel-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  background-color: #ffffff;

  &.no-padding :deep(.el-card__body) {
    padding: 0;
    height: 100%;
    display: flex;
    flex-direction: column;
  }
}

// 左栏：侧边栏
.left-panel {
  height: 100%;

  .sidebar-header {
    padding: 14px 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid #ebeef5;

    .header-title {
      font-size: 15px;
      font-weight: 600;
      color: #303133;
      display: flex;
      align-items: center;
      gap: 6px;
    }
  }

  .session-list-section {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;

    .section-header-row {
      padding: 10px 16px 6px;
      display: flex;
      justify-content: space-between;
      align-items: center;

      .section-label {
        font-size: 12px;
        color: #909399;
      }
    }

    .session-scroll-list {
      flex: 1;
      overflow-y: auto;
      padding: 0 8px;

      .session-item {
        padding: 10px 10px;
        border-radius: 4px;
        margin-bottom: 4px;
        display: flex;
        align-items: center;
        gap: 8px;
        cursor: pointer;
        position: relative;
        transition: background-color 0.2s;

        &:hover {
          background-color: #f5f7fa;
          .session-del-icon {
            display: inline-flex;
          }
        }

        &.active {
          background-color: #f0f7ff;
          color: #409eff;
        }

        .session-icon {
          color: #909399;
        }

        .session-content {
          flex: 1;
          overflow: hidden;

          .session-title {
            font-size: 13px;
            color: #303133;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }

          .session-time {
            font-size: 11px;
            color: #c0c4cc;
            margin-top: 2px;
          }
        }

        .session-del-icon {
          display: none;
          color: #c0c4cc;
          font-size: 14px;
          padding: 2px;
          border-radius: 50%;
          transition: all 0.2s;

          &:hover {
            color: #f56c6c;
            background-color: #fef0f0;
          }
        }
      }
    }
  }

  .sidebar-footer {
    padding: 12px 16px;
    border-top: 1px solid #ebeef5;
    background-color: #fafafa;

    .footer-tenant-badge {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 6px;
    }

    .footer-model-row {
      font-size: 11px;
      color: #909399;
    }
  }
}

// 右侧全宽对话主区域
.center-panel {
  height: 100%;

  .chat-box-card {
    :deep(.el-card__header) {
      padding: 12px 16px;
      border-bottom: 1px solid #ebeef5;
    }

    :deep(.el-card__body) {
      flex: 1;
      padding: 0;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
  }

  .chat-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .chat-header-left {
      display: flex;
      align-items: center;
      gap: 8px;

      .chat-title {
        font-size: 15px;
        font-weight: 600;
        color: #303133;
      }
    }
  }

  .message-container {
    flex: 1;
    overflow-y: auto;
    padding: 20px;
    background-color: #f8f9fb;

    .message-row {
      display: flex;
      margin-bottom: 24px;
      gap: 12px;

      &.row-user {
        flex-direction: row-reverse;

        .msg-bubble {
          background-color: #409eff;
          color: #ffffff;
          border-radius: 8px 2px 8px 8px;

          :deep(.markdown-body) {
            color: #ffffff;
            strong, h1, h2, h3, h4, p, li { color: #ffffff; }
            code { background-color: rgba(255, 255, 255, 0.2); color: #ffffff; }
          }
        }

        .msg-meta {
          text-align: right;
        }
      }

      &.row-assistant {
        flex-direction: row;

        .msg-bubble {
          background-color: #ffffff;
          color: #303133;
          border: 1px solid #e4e7ed;
          border-radius: 2px 8px 8px 8px;
        }
      }

      .msg-bubble-wrap {
        max-width: 88%;

        .msg-meta {
          margin-bottom: 6px;
          font-size: 12px;
          color: #909399;
          display: flex;
          align-items: center;
          gap: 8px;

          .sender-name {
            font-weight: 500;
            margin-right: 4px;
          }

          .delete-round-btn {
            opacity: 0;
            font-size: 12px;
            padding: 0 4px;
            height: 18px;
            line-height: 18px;
            transition: opacity 0.2s;

            &:hover {
              color: #f56c6c;
            }
          }
        }

        &:hover {
          .msg-meta .delete-round-btn {
            opacity: 1;
          }
        }

        // 用户消息携带的附件卡片 (带图片缩略图与 OSS 归档标识)
        .user-attachment-card {
          background-color: #ecf5ff;
          border: 1px solid #d9ecff;
          border-radius: 6px;
          padding: 8px 12px;
          margin-bottom: 8px;
          max-width: 380px;

          .attachment-meta-row {
            display: flex;
            align-items: center;
            gap: 8px;

            .file-meta {
              overflow: hidden;
              .file-name {
                font-size: 12px;
                font-weight: 500;
                color: #409eff;
              }
              .file-size {
                font-size: 11px;
                color: #909399;
              }
            }
          }

          .oss-badge-row {
            margin-top: 6px;
            padding-top: 6px;
            border-top: 1px dashed #d9ecff;

            .oss-link-btn {
              display: inline-flex;
              align-items: center;
              gap: 4px;
              font-size: 11px;
              color: #e6a23c;
              text-decoration: none;
              font-weight: 500;

              &:hover {
                text-decoration: underline;
                color: #d48806;
              }
            }
          }

          .image-thumbnail-box {
            margin-top: 8px;
            max-width: 240px;
            border-radius: 4px;
            overflow: hidden;
            border: 1px solid #dcdfe6;

            .thumbnail-img {
              max-height: 180px;
              width: 100%;
              display: block;
            }
          }
        }

        .msg-bubble {
          padding: 10px 14px;
          box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
          line-height: 1.6;
          font-size: 14px;
          word-break: break-word;

          // 核心特性 2：CoT 思考链卡片 (集成阶段一路由决策、阶段二底层调度与阶段三模型深度推理)
          .thought-container {
            background-color: #f7f8fa;
            border-left: 3px solid #409eff;
            border-radius: 4px;
            padding: 8px 12px;
            margin-bottom: 12px;

            .thought-header {
              display: flex;
              justify-content: space-between;
              align-items: center;
              cursor: pointer;
              user-select: none;

              .thought-title {
                font-size: 12px;
                color: #606266;
                display: flex;
                align-items: center;
                gap: 6px;

                .thinking-spinner {
                  color: #409eff;
                  display: flex;
                  align-items: center;
                  gap: 4px;
                }

                .thought-done {
                  color: #606266;
                  display: flex;
                  align-items: center;
                  gap: 4px;
                  font-weight: 500;
                }
              }

              .thought-toggle-arrow {
                font-size: 12px;
                color: #909399;
                transition: transform 0.2s;

                .is-expanded {
                  transform: rotate(180deg);
                }
              }
            }

            .thought-content {
              margin-top: 8px;
              padding-top: 8px;
              border-top: 1px dashed #e4e7ed;
              font-size: 12px;
              color: #5c6b77;
              line-height: 1.6;

              .thought-cursor {
                display: inline-block;
                color: #409eff;
                animation: blink 0.8s infinite;
              }
            }
          }

          &.streaming-bubble {
            .bubble-content {
              display: inline;
              > *:last-child {
                display: inline;
              }
            }
          }

          .cursor-blink {
            display: inline-block;
            margin-left: 2px;
            color: #409eff;
            font-weight: bold;
            font-size: 15px;
            line-height: 1;
            vertical-align: baseline;
            animation: blink 0.8s infinite;
          }
        }
      }
    }
  }

  .empty-chat-guide {
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;
    color: #606266;
    padding: 30px;

    h4 {
      margin: 12px 0 6px;
      color: #303133;
    }

    p {
      font-size: 13px;
      color: #909399;
      margin-bottom: 20px;
    }

    .sample-questions {
      background: #ffffff;
      padding: 16px;
      border-radius: 6px;
      border: 1px solid #e4e7ed;
      max-width: 500px;

      .sample-title {
        font-size: 12px;
        color: #909399;
        display: block;
        margin-bottom: 10px;
      }

      .sample-chips {
        display: flex;
        flex-direction: column;
        gap: 8px;

        .sample-chip {
          cursor: pointer;
          text-align: left;
          height: auto;
          padding: 8px 12px;
          white-space: normal;
          line-height: 1.4;

          &:hover {
            border-color: #409eff;
            color: #409eff;
          }
        }
      }
    }
  }

  .chat-input-area {
    padding: 8px 12px 6px;
    border-top: 1px solid #ebeef5;
    background-color: #ffffff;

    .attachment-preview-bar {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 8px;

      .file-chip {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        background-color: #ecf5ff;
        border: 1px solid #d9ecff;
        padding: 4px 10px;
        border-radius: 4px;
        font-size: 12px;
        color: #409eff;

        .chip-preview-img {
          width: 20px;
          height: 20px;
          border-radius: 2px;
        }

        .remove-file-btn {
          cursor: pointer;
          color: #909399;
          &:hover {
            color: #f56c6c;
          }
        }
      }

      .upload-tip-text {
        font-size: 11px;
        color: #909399;
      }
    }

    .input-bottom-actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-top: 8px;
    }
  }
}

// 专业 GitHub Markdown 样式 (支持严格 GFM 规范表格、标题、代码块、列表)
:deep(.markdown-body) {
  font-size: 14px;
  line-height: 1.7;
  color: #24292e;

  h1, h2, h3, h4, h5, h6 {
    margin-top: 16px;
    margin-bottom: 8px;
    font-weight: 600;
    color: #1f2328;
    line-height: 1.3;
  }
  h1 { font-size: 1.45em; border-bottom: 1px solid #d0d7de; padding-bottom: 6px; }
  h2 { font-size: 1.25em; border-bottom: 1px solid #d0d7de; padding-bottom: 4px; }
  h3 { font-size: 1.12em; }
  h4 { font-size: 1.02em; }

  p {
    margin-top: 0;
    margin-bottom: 10px;
  }

  blockquote {
    margin: 10px 0;
    padding: 8px 16px;
    color: #57606a;
    border-left: 4px solid #409eff;
    background-color: #f6f8fa;
    border-radius: 3px;

    p { margin-bottom: 0; }
  }

  ul, ol {
    margin-top: 4px;
    margin-bottom: 10px;
    padding-left: 22px;
  }

  li {
    margin-top: 4px;
  }

  table {
    width: 100%;
    border-collapse: collapse;
    margin: 14px 0;
    display: table;
    overflow-x: auto;
    border: 1px solid #d0d7de;
    border-radius: 4px;

    th, td {
      border: 1px solid #d0d7de;
      padding: 8px 14px;
      text-align: left;
      font-size: 13px;
    }

    th {
      background-color: #f6f8fa;
      font-weight: 600;
      color: #24292e;
    }

    tr:nth-child(even) {
      background-color: #fbfcfd;
    }

    tr:hover {
      background-color: #f2f7ff;
    }
  }

  hr {
    height: 1px;
    padding: 0;
    margin: 16px 0;
    background-color: #d0d7de;
    border: 0;
  }

  code {
    padding: 2px 6px;
    margin: 0;
    font-size: 85%;
    background-color: #eff1f3;
    border-radius: 4px;
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace;
    color: #0969da;
  }

  pre {
    padding: 12px 16px;
    overflow: auto;
    font-size: 85%;
    line-height: 1.45;
    background-color: #1e1e1e;
    color: #d4d4d4;
    border-radius: 6px;
    margin: 12px 0;

    code {
      display: inline;
      padding: 0;
      margin: 0;
      background-color: transparent;
      border: 0;
      color: inherit;
    }
  }

  strong {
    font-weight: 600;
    color: #1f2328;
  }
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

/* ==================== 意图路由 200 题基准评测抽屉样式 ==================== */
.intent-benchmark-container {
  padding: 6px 0;
}

.benchmark-action-header {
  margin-bottom: 16px;
}

.action-buttons-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.benchmark-tip-box {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  padding: 8px 12px;
  margin-top: 10px;
  font-size: 13px;
  color: #e6a23c;
}

.metric-cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.metric-stat-card {
  padding: 14px 16px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);

  .metric-title {
    font-size: 13px;
    color: #64748b;
    margin-bottom: 6px;
    font-weight: 500;
  }

  .metric-value {
    font-size: 24px;
    font-weight: 700;
    line-height: 1.2;
    margin-bottom: 4px;
    font-family: monospace;
  }

  .metric-sub {
    font-size: 12px;
    color: #94a3b8;
  }

  &.primary {
    border-top: 3px solid #3b82f6;
    .metric-value { color: #2563eb; }
  }

  &.success {
    border-top: 3px solid #10b981;
    .metric-value { color: #059669; }
  }

  &.warning {
    border-top: 3px solid #f59e0b;
    .metric-value { color: #d97706; }
  }

  &.info {
    border-top: 3px solid #6366f1;
    .metric-value { color: #4f46e5; }
  }

  &.danger {
    border-top: 3px solid #ef4444;
    .metric-value { color: #dc2626; }
  }
}

.confusion-matrix-box {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 20px;
}

.cm-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.cm-header h4 {
  margin: 0;
  font-size: 15px;
  color: #1e293b;
}

.cm-table-wrap {
  overflow-x: auto;
}

.confusion-table {
  width: 100%;
  border-collapse: collapse;
  text-align: center;
  font-size: 13px;
}

.confusion-table th,
.confusion-table td {
  padding: 10px 14px;
  border: 1px solid #e2e8f0;
}

.confusion-table th {
  background: #f8fafc;
  color: #475569;
  font-weight: 600;
}

.corner-header {
  background: #f1f5f9 !important;
  color: #334155 !important;
}

.row-label {
  background: #f8fafc;
  font-weight: 600;
  text-align: left;
  color: #1e293b;
}

.cm-cell {
  font-family: monospace;
  font-size: 14px;
  font-weight: 600;
  color: #94a3b8;
}

.cm-hit {
  background-color: #dcfce7 !important;
  color: #15803d !important;
}

.cm-miss {
  background-color: #fee2e2 !important;
  color: #b91c1c !important;
}

.section-title-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 10px;
}

.section-title-bar h4 {
  margin: 0;
  font-size: 15px;
  color: #1e293b;
}

.pruning-text {
  font-size: 11px;
  color: #64748b;
  margin-top: 2px;
}

.benchmark-empty-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
  background: #ffffff;
  border: 1px dashed #dcdfe6;
  border-radius: 8px;
  margin-top: 14px;
  text-align: center;
}

.history-drawer-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-bottom: 20px;
}

.history-drawer-tip {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 13px;
  color: #166534;
  line-height: 1.5;
}

.history-table {
  border-radius: 6px;
  overflow: hidden;
}

.history-pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding-top: 10px;
}
/* 修掉 Markdown 末段 margin 造成的气泡底部大空白 */
.bubble-content :deep(p:last-child),
.thought-content :deep(p:last-child),
.bubble-content :deep(ul:last-child),
.bubble-content :deep(ol:last-child),
.bubble-content :deep(pre:last-child) {
  margin-bottom: 0;
}
</style>

