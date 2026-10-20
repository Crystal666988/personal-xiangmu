<template>
  <div class="workbench-container">
    <!-- 1. 欢迎卡片 (去除后端状态与冗余标语) -->
    <el-card shadow="never" class="welcome-card">
      <div class="welcome-box">
        <div class="user-greeting">
          <el-avatar :size="52" class="avatar-large">
            {{ userStore.displayName ? userStore.displayName.slice(0, 1) : 'A' }}
          </el-avatar>
          <div class="greeting-text">
            <h3>早安，{{ userStore.displayName || '系统超级管理员' }}，祝你工作顺利！</h3>
            <p>当前登录角色：<el-tag size="small" type="success">{{ userStore.userInfo?.role || 'admin' }}</el-tag></p>
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

    <!-- 2. RAG 两阶段架构控制台 (阶段一：离线入库 ｜ 阶段二：在线问答 ｜ 阶段三：真实 AI 出题评测) -->
    <el-card shadow="never" class="rag-workbench-card">
      <el-tabs v-model="activeTab" class="rag-tabs" type="border-card">
        <!-- ==================== 阶段一：知识库离线入库与切片管理 ==================== -->
        <el-tab-pane name="offline">
          <template #label>
            <span class="tab-label">
              <el-icon><FolderOpened /></el-icon>
              <span>阶段一：知识库离线入库与切片</span>
              <el-badge :value="documents.length" class="tab-badge" type="primary" />
            </span>
          </template>

          <!-- 顶部统计与操作栏 -->
          <div class="kb-action-bar">
            <div class="action-left">
              <input
                ref="kbFileInputRef"
                type="file"
                style="display: none"
                accept=".pdf,.md,.txt,.docx,.json"
                @change="handleKbFileSelected"
              />
              <el-button type="primary" :loading="isUploadingDoc" @click="triggerKbFileInput">
                <el-icon v-if="!isUploadingDoc"><Upload /></el-icon>
                <span>上传文档执行 AST 切片并入库</span>
              </el-button>
              <el-button :icon="Refresh" @click="loadDocuments">刷新列表</el-button>
            </div>

            <div class="action-right">
              <span class="kb-summary-stat">
                已索引文档：<strong>{{ documents.length }}</strong> 篇 ｜ 倒排索引切片总数：<strong>{{ totalChunksCount }}</strong> 个
              </span>
            </div>
          </div>

          <!-- 文档列表表格 -->
          <el-table :data="documents" v-loading="isLoadingDocs" border stripe class="doc-table">
            <el-table-column label="文档名称" min-width="260">
              <template #default="{ row }">
                <div class="doc-name-cell">
                  <el-icon :size="18" color="#409eff" class="mr-2"><Document /></el-icon>
                  <span class="doc-name-text" :title="row.name">{{ row.name }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column prop="type" label="格式" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type === 'MARKDOWN' ? 'primary' : 'warning'">{{ row.type }}</el-tag>
              </template>
            </el-table-column>

            <el-table-column label="大小" width="110" align="center">
              <template #default="{ row }">
                <span>{{ formatFileSize(row.size) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="AST 切片数" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" type="success" effect="plain">{{ row.chunkCount }} 切片</el-tag>
              </template>
            </el-table-column>

            <el-table-column prop="status" label="状态" width="120" align="center">
              <template #default="{ row }">
                <el-tag size="small" type="success">已建倒排索引</el-tag>
              </template>
            </el-table-column>

            <el-table-column prop="uploadTime" label="入库时间" width="170" align="center" />

            <el-table-column label="操作" width="280" align="center" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="viewDocChunks(row)">
                  <el-icon><View /></el-icon> 查看切片
                </el-button>
                <el-button size="small" type="success" link @click="startQaWithDoc(row)">
                  <el-icon><ChatDotRound /></el-icon> 在线问答
                </el-button>
                <el-button size="small" type="warning" link @click="startBenchmarkWithDoc(row)">
                  <el-icon><DataAnalysis /></el-icon> AI 评测
                </el-button>
                <el-button size="small" type="danger" link @click="handleDeleteDoc(row)">
                  <el-icon><Delete /></el-icon> 删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ==================== 阶段二：知识库在线问答 ==================== -->
        <el-tab-pane name="online-qa">
          <template #label>
            <span class="tab-label">
              <el-icon><ChatDotRound /></el-icon>
              <span>阶段二：知识库在线问答 (切片溯源)</span>
            </span>
          </template>

          <div class="qa-container">
            <!-- 问答控制面板 -->
            <div class="qa-control-panel">
              <div class="control-row">
                <div class="control-item">
                  <span class="control-label">检索文档范围：</span>
                  <el-select v-model="selectedDocIdForQa" placeholder="选择检索文档" style="width: 280px">
                    <el-option label="🌐 全部知识库 (全局倒排检索)" value="all" />
                    <el-option
                      v-for="d in documents"
                      :key="d.id"
                      :label="`📄 ${d.name} (${d.chunkCount}切片)`"
                      :value="d.id"
                    />
                  </el-select>
                </div>

                <div class="control-item">
                  <span class="control-label">Top-K 召回切片数：</span>
                  <el-input-number v-model="qaTopK" :min="1" :max="8" size="default" />
                </div>
              </div>

              <!-- 快捷问题推荐 -->
              <div class="quick-questions">
                <span class="quick-label">💡 常见提问：</span>
                <el-tag
                  v-for="(tag, idx) in presetQuestions"
                  :key="idx"
                  class="quick-tag"
                  effect="plain"
                  @click="usePresetQuestion(tag)"
                >
                  {{ tag }}
                </el-tag>
              </div>

              <!-- 提问输入框 -->
              <div class="qa-input-box">
                <el-input
                  v-model="qaInputText"
                  type="textarea"
                  :rows="3"
                  placeholder="输入任何与知识库文档相关的问题，系统将执行真实 BM25 倒排切片检索，并由大模型结合切片内容严密作答..."
                  @keydown.enter.ctrl="handleSendQa"
                />
                <div class="qa-submit-bar">
                  <span class="submit-tip">按 Ctrl + Enter 快速提问</span>
                  <div class="qa-btn-group">
                    <el-button type="info" plain size="large" @click="openQaHistoryDrawer">
                      <el-icon><List /></el-icon>
                      <span>问答历史与审计 ({{ qaHistoryTotal }})</span>
                    </el-button>
                    <el-button type="primary" size="large" :loading="isAskingQa" @click="handleSendQa">
                      <el-icon v-if="!isAskingQa"><ChatDotRound /></el-icon>
                      <span>检索并由 AI 回答</span>
                    </el-button>
                  </div>
                </div>
              </div>
            </div>

            <!-- 问答结果展示区域 -->
            <div v-if="qaResponse" class="qa-result-panel">
              <el-divider content-position="left">
                <span class="divider-title">💬 大模型在线回答 (耗时: {{ qaResponse.latencyMs }}ms ｜ 模型: {{ qaResponse.modelUsed }})</span>
              </el-divider>

              <div class="qa-answer-card">
                <div class="answer-text markdown-body" v-html="renderMarkdown(qaResponse.answer)"></div>

                <!-- 准确率人工审计打分栏 -->
                <div class="qa-rating-bar">
                  <span class="rating-tip">🎯 问答准确率审计：</span>
                  <el-button
                    size="small"
                    :type="currentQaRating === 'ACCURATE' ? 'success' : 'default'"
                    :plain="currentQaRating !== 'ACCURATE'"
                    @click="handleRateQa(qaResponse.id, 'ACCURATE')"
                  >
                    👍 准确规范
                  </el-button>
                  <el-button
                    size="small"
                    :type="currentQaRating === 'INACCURATE' ? 'danger' : 'default'"
                    :plain="currentQaRating !== 'INACCURATE'"
                    @click="handleRateQa(qaResponse.id, 'INACCURATE')"
                  >
                    👎 存在偏差/幻觉
                  </el-button>
                  <span v-if="currentQaRating" class="rating-feedback-status">
                    已打标: {{ currentQaRating === 'ACCURATE' ? '✅ 准确规范' : '⚠️ 存在偏差' }}
                  </span>
                </div>
              </div>

              <!-- 真实溯源引用切片 -->
              <div class="qa-citations-section">
                <div class="citation-header">
                  <el-icon color="#409eff"><Document /></el-icon>
                  <span><strong>📌 检索到的参考切片溯源引用</strong> (共召回 {{ qaResponse.citations?.length || 0 }} 个切片)：</span>
                </div>

                <div class="citation-grid">
                  <div
                    v-for="(cite, idx) in qaResponse.citations"
                    :key="cite.chunkId"
                    class="citation-card"
                  >
                    <div class="cite-top">
                      <el-tag size="small" type="primary">切片 {{ idx + 1 }}</el-tag>
                      <el-tag size="small" type="success" title="70% 稠密向量余弦相似度 + 30% BM25 综合归一化融合得分">
                        🎯 融合得分: {{ cite.score }}
                      </el-tag>
                      <el-tag v-if="cite.vectorSimilarity != null" size="small" type="warning" title="阿里百炼 text-embedding-v3 1024 维特征向量余弦相似度">
                        🧬 向量相似度: {{ cite.vectorSimilarity }}
                      </el-tag>
                      <el-tag v-if="cite.bm25Score != null" size="small" type="info" title="BM25 倒排索引词频统计原生评分">
                        📄 BM25: {{ cite.bm25Score }}
                      </el-tag>
                      <el-tag size="small" type="info">{{ cite.chunkType }}</el-tag>
                      <span class="cite-doc text-truncate" :title="cite.docName">《{{ cite.docName }}》</span>
                    </div>
                    <div class="cite-breadcrumb font-mono text-truncate" :title="cite.breadcrumb">
                      📍 路径: {{ cite.breadcrumb }}
                    </div>
                    <div class="cite-content markdown-body" v-html="renderMarkdown(cite.content)"></div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- ==================== 阶段三：真实 AI 大模型出题与基准评测 ==================== -->
        <el-tab-pane name="benchmark">
          <template #label>
            <span class="tab-label">
              <el-icon><DataAnalysis /></el-icon>
              <span>阶段三：真实 AI 大模型出题评测</span>
            </span>
          </template>

          <div class="benchmark-container">
            <!-- 评测发起控制卡 -->
            <div class="benchmark-control-bar">
              <div class="benchmark-config-left">
                <div class="config-item">
                  <span class="config-label">评测切片来源：</span>
                  <el-select v-model="selectedDocIdForBenchmark" placeholder="选择评测文档" style="width: 280px">
                    <el-option label="🌐 全部知识库切片" value="all" />
                    <el-option
                      v-for="d in documents"
                      :key="d.id"
                      :label="`📄 ${d.name} (${d.chunkCount}切片)`"
                      :value="d.id"
                    />
                  </el-select>
                </div>

                <div class="config-item">
                  <span class="config-label">AI 真实出题数：</span>
                  <el-select v-model="benchmarkCount" style="width: 130px">
                    <el-option label="10 题 (深度)" :value="10" />
                    <el-option label="20 题 (标准)" :value="20" />
                    <el-option label="50 题 (全量)" :value="50" />
                  </el-select>
                </div>

                <el-button
                  type="success"
                  size="large"
                  :loading="isRunningAiBenchmark"
                  @click="handleRunAiBenchmark"
                >
                  <el-icon v-if="!isRunningAiBenchmark"><VideoPlay /></el-icon>
                  <span>调用 AI 大模型阅读切片并出题评测</span>
                </el-button>

                <el-button
                  type="info"
                  plain
                  size="large"
                  @click="openBenchmarkHistoryDrawer"
                >
                  <el-icon><Clock /></el-icon>
                  <span>历史评测报告 ({{ benchmarkHistoryTotal }} 份)</span>
                </el-button>
              </div>

              <div class="benchmark-config-right">
                <el-tag size="small" type="info" effect="plain">
                  当前配置模型: {{ activeModelInfo }}
                </el-tag>
              </div>
            </div>

            <!-- 正在运行 AI 评测的动态状态卡片 -->
            <div v-if="isRunningAiBenchmark" class="benchmark-running-card">
              <div class="running-header">
                <el-icon class="is-loading" :size="22" color="#409eff"><Loading /></el-icon>
                <span><strong>AI 大模型正在阅读切片并生成高质量考题中...</strong></span>
              </div>
              <p class="running-desc">大模型正在对切片进行深度语义理解，逆向生成 4 种维度的考题与黄金标准答案。出题完成后，系统将自动发起全量切片 1024 维稠密向量与 BM25 混合检索，检验 Top-4 召回率、置信度门禁与 Bad-Case，请稍候约 10~25 秒...</p>
              <el-progress :percentage="80" :indeterminate="true" :duration="2" status="success" />
            </div>

            <!-- 空状态引导提示 -->
            <div v-else-if="!benchmarkReport" class="benchmark-empty-card">
              <el-icon :size="48" color="#c0c4cc"><DataAnalysis /></el-icon>
              <div class="empty-title">暂无评测报告</div>
              <div class="empty-desc">请选择切片来源（推荐选择全部知识库或指定文档），点击上方绿色按钮即可让 AI 深度阅读切片出题，全自动检验并生成召回看板！</div>
            </div>

            <!-- 评测结果报告 -->
            <div v-if="benchmarkReport" class="benchmark-report-section">
              <el-divider content-position="left">
                <div class="divider-title-group">
                  <span class="divider-title">
                    📊 AI 评测报告 (任务ID: <code>{{ benchmarkReport.benchmarkId }}</code> ｜ 倒排切片数: {{ benchmarkReport.totalChunksIndexed }})
                  </span>
                  <el-button type="primary" link size="small" style="margin-left: 12px" @click="openBenchmarkHistoryDrawer">
                    <el-icon><Clock /></el-icon>
                    <span>切换历史评测报告</span>
                  </el-button>
                </div>
              </el-divider>

              <!-- 4 大核心指标卡片 -->
              <el-row :gutter="14" class="metric-row">
                <el-col :xs="12" :sm="6">
                  <div class="metric-card card-recall">
                    <div class="metric-label">Top-4 召回率 (Recall)</div>
                    <div class="metric-value">{{ benchmarkReport.recallRate ?? 0 }}%</div>
                    <div class="metric-sub">正向命中: {{ benchmarkReport.hitCount }} / {{ benchmarkReport.positiveQuestions ?? benchmarkReport.totalQuestions }} 题</div>
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
                    <div class="metric-sub">防御通过: {{ benchmarkReport.adversarialPassed ?? (benchmarkReport.adversarialQuestions || 0) }} / {{ benchmarkReport.adversarialQuestions ?? 0 }} 题</div>
                  </div>
                </el-col>

                <el-col :xs="12" :sm="6">
                  <div class="metric-card card-latency">
                    <div class="metric-label">单题检索均延 (Latency)</div>
                    <div class="metric-value">{{ benchmarkReport.avgRetrievalLatencyMs ?? 0 }} ms</div>
                    <div class="metric-sub">BM25 倒排索引检索耗时</div>
                  </div>
                </el-col>
              </el-row>

              <!-- 四象限题型统计与 Bad-Case 对策 -->
              <el-row :gutter="14" class="detail-row">
                <el-col :xs="24" :md="10">
                  <div class="sub-panel">
                    <div class="sub-panel-title">🎯 四大象限题目召回率分布</div>
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

                <el-col :xs="24" :md="14">
                  <div class="sub-panel">
                    <div class="sub-panel-title">🔍 真实 Bad-Case 归因溯源与自愈对策</div>
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
                        <span>本次评测题目 100% 全部精准召回，零 Bad-Case！</span>
                      </div>
                    </div>
                  </div>
                </el-col>
              </el-row>

              <!-- AI 真题出题明细与命中详情 (100% 真实 AI 出题检验) -->
              <div v-if="benchmarkReport.questionResults && benchmarkReport.questionResults.length > 0" class="ai-questions-detail-panel">
                <div class="sub-panel-title">
                  📝 AI 真实出题与检索命中详情明细 (共 {{ benchmarkReport.questionResults.length }} 题，真实大模型生成 + 真实倒排命中检验)
                </div>

                <el-table :data="benchmarkReport.questionResults" size="small" border stripe class="questions-table">
                  <el-table-column prop="id" label="题号" width="90" align="center" />
                  
                  <el-table-column prop="categoryName" label="题型" width="105" align="center">
                    <template #default="{ row }">
                      <el-tag size="small" :type="(row.isAdversarial || row.adversarial) ? 'danger' : 'primary'">{{ row.categoryName }}</el-tag>
                    </template>
                  </el-table-column>

                  <el-table-column prop="question" label="AI 真实出的考题" min-width="220" />

                  <el-table-column prop="groundTruthAnswer" label="AI 标准参考答案" min-width="200" />

                  <el-table-column prop="expectedChunkId" label="依据切片 ID" width="160" align="center">
                    <template #default="{ row }">
                      <el-tag size="small" type="info" class="font-mono">{{ row.expectedChunkId }}</el-tag>
                    </template>
                  </el-table-column>

                  <el-table-column label="检索命中状态" width="130" align="center">
                    <template #default="{ row }">
                      <el-tag
                        size="small"
                        :type="(row.isHit || row.hit) ? 'success' : 'danger'"
                        :effect="(row.isHit || row.hit) ? 'dark' : 'light'"
                      >
                        <span v-if="row.isAdversarial || row.adversarial">
                          {{ (row.isHit || row.hit) ? '🛡️ 防御成功' : '⚠️ 越界击穿' }}
                        </span>
                        <span v-else>
                          {{ (row.isHit || row.hit) ? '✓ HIT 命中' : '✗ MISS 未命中' }}
                        </span>
                      </el-tag>
                    </template>
                  </el-table-column>

                  <el-table-column label="实际召回切片" min-width="200">
                    <template #default="{ row }">
                      <div v-if="row.retrievedChunkIds && row.retrievedChunkIds.length > 0" class="recalled-chips">
                        <el-tag
                          v-for="id in row.retrievedChunkIds"
                          :key="id"
                          size="small"
                          :type="isRecalledMatch(row, id) ? 'success' : 'info'"
                          :effect="isRecalledMatch(row, id) ? 'dark' : 'plain'"
                          class="recalled-chip font-mono"
                        >
                          <span v-if="isRecalledMatch(row, id)">🎯 </span>
                          {{ id }}
                        </el-tag>
                      </div>
                      <span v-else class="text-muted">无召回</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- ==================== 阶段四：智能体 Skill 技能中心 ==================== -->
        <el-tab-pane name="skills">
          <template #label>
            <span class="tab-label">
              <el-icon><Lightning /></el-icon>
              <span>阶段四：智能体 Skill 技能中心</span>
              <el-badge :value="skillsList.length" class="tab-badge" type="success" />
            </span>
          </template>

          <div class="skills-container">
            <div class="skills-banner">
              <div class="skills-banner-text">
                <h3>🚀 智能体 Skill 技能编排中心 (Industry-grade Skills)</h3>
                <p>将底层外贸工具（算价、汇率、海运、海关、合规 RAG、Text2SQL）封装为高阶自主技能包，支持声明式提示词注入、热插拔启停与单测调试。</p>
              </div>
              <el-button :icon="Refresh" @click="loadSkillsList">刷新技能状态</el-button>
            </div>

            <div v-loading="isLoadingSkills" class="skills-grid mt-4">
              <el-card
                v-for="skill in skillsList"
                :key="skill.id"
                class="skill-card"
                :class="{ 'is-active': skill.status === 'ACTIVE' }"
                shadow="hover"
              >
                <div class="skill-card-header">
                  <div class="skill-title-wrap">
                    <el-icon :size="24" class="skill-icon"><component :is="skill.icon || 'Opportunity'" /></el-icon>
                    <div>
                      <h4 class="skill-name">{{ skill.name }}</h4>
                      <el-tag size="small" type="primary" class="skill-category">{{ skill.category }}</el-tag>
                    </div>
                  </div>
                  <el-switch
                    v-model="skill.status"
                    active-value="ACTIVE"
                    inactive-value="INACTIVE"
                    active-text="启用"
                    inactive-text="停用"
                    inline-prompt
                    @change="handleToggleSkill(skill)"
                  />
                </div>

                <p class="skill-desc">{{ skill.description }}</p>

                <!-- 能力标签 -->
                <div class="skill-tags">
                  <el-tag v-for="tag in skill.tags" :key="tag" size="small" type="info" effect="plain" class="skill-tag">
                    # {{ tag }}
                  </el-tag>
                </div>

                <!-- 绑定原子工具 -->
                <div class="skill-bound-tools">
                  <div class="sub-label">🛠️ 绑定的原子工具链：</div>
                  <div class="tool-pills">
                    <el-tag
                      v-for="t in skill.boundTools"
                      :key="t.id"
                      size="small"
                      type="success"
                      class="tool-pill"
                    >
                      {{ t.name }} ({{ t.id }})
                    </el-tag>
                  </div>
                </div>

                <!-- 性能遥测 -->
                <div class="skill-telemetry-bar">
                  <span class="telemetry-item">调用: <strong>{{ skill.invocationCount }}</strong> 次</span>
                  <span class="telemetry-item">成功率: <strong class="text-success">{{ skill.successRate }}%</strong></span>
                  <span class="telemetry-item">平均耗时: <strong>{{ skill.avgLatencyMs }}ms</strong></span>
                </div>

                <div class="skill-card-footer">
                  <el-button type="primary" size="small" plain @click="openSkillTestModal(skill)">
                    <el-icon><CaretRight /></el-icon> 单测运行
                  </el-button>
                </div>
              </el-card>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 切片详情查看抽屉 (Drawer) -->
    <el-drawer
      v-model="chunkDrawerVisible"
      :title="`切片透视：《${currentInspectingDoc?.name}》 (共 ${inspectingChunks.length} 个切片)`"
      size="60%"
      direction="rtl"
    >
      <div v-loading="isLoadingChunks" class="drawer-content">
        <div v-if="inspectingChunks.length === 0 && !isLoadingChunks" class="empty-chunks">
          暂无切片数据
        </div>
        <div
          v-for="(chunk, idx) in inspectingChunks"
          :key="chunk.chunkId || idx"
          class="inspect-chunk-card"
        >
          <div class="chunk-card-meta">
            <el-tag size="small" type="primary">{{ chunk.chunkId }}</el-tag>
            <el-tag size="small" type="info">{{ chunk.chunkType || 'TEXT' }}</el-tag>
            <span class="chunk-path font-mono text-truncate" :title="chunk.breadcrumbPath">
              📍 路径: {{ chunk.breadcrumbPath }}
            </span>
            <span class="chunk-chars">({{ chunk.content?.length || 0 }} 字符)</span>
          </div>
          <div class="chunk-card-text font-mono">
            {{ chunk.content }}
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 评测报告历史记录抽屉 (含分页查询与报告回溯) -->
    <el-drawer
      v-model="benchmarkHistoryDrawerVisible"
      title="📜 RAG 真实基准评测历史报告 (MySQL 持久化)"
      size="70%"
      direction="rtl"
    >
      <div v-loading="isLoadingBenchmarkHistory" class="history-drawer-content">
        <div class="history-drawer-tip">
          <span>💡 每次由大模型深度阅读切片逆向出的考题、判定标准与召回明细均已持久化至 MySQL。点击<strong>【回溯此报告】</strong>即可完整加载并查看当次的所有考题明细与 Bad-Case。</span>
        </div>

        <el-table :data="benchmarkHistoryRecords" size="small" border stripe class="history-table">
          <el-table-column prop="id" label="任务ID" width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="font-mono text-truncate">{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="评测时间" width="150" align="center" />
          <el-table-column prop="docName" label="评测对象" min-width="140" show-overflow-tooltip />
          <el-table-column prop="totalQuestions" label="考题数" width="70" align="center" />
          <el-table-column label="Top-4 召回率" width="115" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.recallRate >= 80 ? 'success' : 'warning'">
                {{ row.recallRate }}% ({{ row.hitCount }}/{{ row.positiveQuestions || row.totalQuestions }})
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="对抗防御率" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.adversarialRejectionRate >= 90 ? 'success' : 'danger'">
                {{ row.adversarialRejectionRate }}%
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="badCaseCount" label="缺陷数" width="70" align="center">
            <template #default="{ row }">
              <span :class="{ 'text-danger': row.badCaseCount > 0 }">{{ row.badCaseCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="modelUsed" label="大模型" width="110" align="center" show-overflow-tooltip />
          <el-table-column label="操作" width="150" align="center" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="handleViewHistoricalBenchmark(row)">
                回溯报告
              </el-button>
              <el-button type="danger" link size="small" @click="handleDeleteHistoricalBenchmark(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="history-pagination-bar">
          <el-pagination
            v-model:current-page="benchmarkHistoryPageNum"
            v-model:page-size="benchmarkHistoryPageSize"
            :total="benchmarkHistoryTotal"
            :page-sizes="[5, 10, 20]"
            layout="total, sizes, prev, pager, next"
            @size-change="loadBenchmarkHistory"
            @current-change="loadBenchmarkHistory"
          />
        </div>
      </div>
    </el-drawer>

    <!-- 在线问答历史记录与审计抽屉 -->
    <el-drawer
      v-model="qaHistoryDrawerVisible"
      title="💬 RAG 在线切片溯源问答历史与准确率审计"
      size="65%"
      direction="rtl"
    >
      <div v-loading="isLoadingQaHistory" class="history-drawer-content">
        <div class="history-search-bar">
          <el-input
            v-model="qaHistoryKeyword"
            placeholder="搜索历史问答问题关键字..."
            clearable
            style="width: 280px"
            @clear="loadQaHistory"
            @keyup.enter="loadQaHistory"
          >
            <template #append>
              <el-button :icon="Search" @click="loadQaHistory" />
            </template>
          </el-input>
          <span class="history-stats-badge">共记录 {{ qaHistoryTotal }} 轮真实问答</span>
        </div>

        <div v-if="qaHistoryRecords.length === 0 && !isLoadingQaHistory" class="empty-chunks">
          暂无问答历史记录
        </div>

        <div
          v-for="item in qaHistoryRecords"
          :key="item.id"
          class="qa-history-card"
        >
          <div class="qa-history-top">
            <span class="qa-history-q font-bold">❓ {{ item.question }}</span>
            <div class="qa-history-rating">
              <el-tag
                v-if="item.userRating === 'ACCURATE'"
                size="small"
                type="success"
                effect="dark"
              >
                👍 准确
              </el-tag>
              <el-tag
                v-else-if="item.userRating === 'INACCURATE'"
                size="small"
                type="danger"
                effect="dark"
              >
                👎 偏差
              </el-tag>
              <el-tag v-else size="small" type="info">待审计</el-tag>
            </div>
          </div>

          <div class="qa-history-answer font-mono">{{ item.answer }}</div>

          <div class="qa-history-meta">
            <span>📚 范围: 《{{ item.docName }}》</span>
            <span>📌 召回切片: {{ item.citationCount }} 个</span>
            <span>⚡ 耗时: {{ item.latencyMs }}ms</span>
            <span>🕒 {{ item.createTime }}</span>
            <div class="qa-history-actions">
              <el-button
                size="small"
                link
                type="success"
                @click="handleRateQa(item.id, 'ACCURATE')"
              >
                👍 评为准确
              </el-button>
              <el-button
                size="small"
                link
                type="danger"
                @click="handleRateQa(item.id, 'INACCURATE')"
              >
                👎 评为偏差
              </el-button>
              <el-button
                size="small"
                link
                type="primary"
                @click="handleInspectHistoricalQa(item)"
              >
                载入主视图
              </el-button>
              <el-button
                size="small"
                link
                type="danger"
                @click="handleDeleteQaRecord(item)"
              >
                删除
              </el-button>
            </div>
          </div>
        </div>

        <div class="history-pagination-bar">
          <el-pagination
            v-model:current-page="qaHistoryPageNum"
            v-model:page-size="qaHistoryPageSize"
            :total="qaHistoryTotal"
            :page-sizes="[5, 10, 20]"
            layout="total, sizes, prev, pager, next"
            @size-change="loadQaHistory"
            @current-change="loadQaHistory"
          />
        </div>
      </div>
    </el-drawer>

    <!-- 技能单测调试弹窗 (Dialog) -->
    <el-dialog
      v-model="skillTestModalVisible"
      :title="`🧪 智能体技能单测运行：${currentTestingSkill?.name || ''}`"
      width="680px"
      destroy-on-close
    >
      <div v-if="currentTestingSkill" class="skill-test-body">
        <div class="skill-test-header-meta">
          <el-tag size="small">{{ currentTestingSkill.category }}</el-tag>
          <span class="text-muted ml-2">已绑定原子工具：{{ currentTestingSkill.toolIds?.join(', ') }}</span>
        </div>

        <div class="mt-3">
          <div class="sub-label font-bold mb-1">入参配置 (JSON):</div>
          <el-input
            v-model="skillTestParamsText"
            type="textarea"
            :rows="6"
            class="font-mono"
            placeholder="请输入入参 JSON..."
          />
        </div>

        <div v-if="skillTestResult" class="skill-result-box mt-3">
          <div class="result-header">
            <el-tag :type="skillTestResult.success ? 'success' : 'danger'" size="small">
              {{ skillTestResult.success ? 'SUCCESS' : 'FAILED' }}
            </el-tag>
            <span class="ml-2 font-mono">总耗时: {{ skillTestResult.totalLatencyMs }}ms</span>
          </div>

          <div class="sub-label font-bold mt-2">聚合执行结果 (Aggregated Payload):</div>
          <pre class="result-code font-mono">{{ JSON.stringify(skillTestResult.aggregatedPayload, null, 2) }}</pre>
        </div>
      </div>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="skillTestModalVisible = false">关闭</el-button>
          <el-button type="primary" :loading="isExecutingSkill" @click="handleExecuteSkill">
            <el-icon v-if="!isExecutingSkill"><CaretRight /></el-icon>
            <span>立即触发执行</span>
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import {
  Cpu,
  ChatDotRound,
  DataAnalysis,
  VideoPlay,
  Upload,
  Document,
  FolderOpened,
  CircleCheckFilled,
  Refresh,
  View,
  Delete,
  Loading,
  Clock,
  List,
  Search,
  Connection,
  Lightning,
  CaretRight,
  InfoFilled,
  TrendCharts,
  Aim,
  Sunny,
  DocumentChecked,
  Opportunity
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import {
  getKnowledgeDocumentsApi,
  uploadKnowledgeDocumentApi,
  getDocumentChunksApi,
  deleteKnowledgeDocumentApi,
  onlineKnowledgeAskApi,
  runAiSyntheticBenchmarkApi,
  getLatestBenchmarkReportApi,
  getBenchmarkReportsPageApi,
  getBenchmarkReportDetailApi,
  deleteBenchmarkReportApi,
  getQaRecordsPageApi,
  rateQaRecordApi,
  deleteQaRecordApi,
  getSkillsListApi,
  toggleSkillApi,
  executeSkillApi
} from '@/api/agent'

marked.setOptions({
  gfm: true,
  breaks: true
})

function renderMarkdown(content) {
  if (!content) return ''
  try {
    return marked.parse(content)
  } catch (e) {
    return content
  }
}

const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('offline')
const documents = ref([])
const isLoadingDocs = ref(false)
const isUploadingDoc = ref(false)
const kbFileInputRef = ref(null)

// 切片抽屉查看
const chunkDrawerVisible = ref(false)
const currentInspectingDoc = ref(null)
const inspectingChunks = ref([])
const isLoadingChunks = ref(false)

// 在线问答状态
const selectedDocIdForQa = ref('all')
const qaTopK = ref(4)
const qaInputText = ref('')
const isAskingQa = ref(false)
const qaResponse = ref(null)

// 问答审计打分与问答历史抽屉
const currentQaRating = ref(null)
const qaHistoryDrawerVisible = ref(false)
const qaHistoryRecords = ref([])
const qaHistoryTotal = ref(0)
const qaHistoryPageNum = ref(1)
const qaHistoryPageSize = ref(10)
const qaHistoryKeyword = ref('')
const isLoadingQaHistory = ref(false)

// 评测报告历史抽屉与分页
const benchmarkHistoryDrawerVisible = ref(false)
const benchmarkHistoryRecords = ref([])
const benchmarkHistoryTotal = ref(0)
const benchmarkHistoryPageNum = ref(1)
const benchmarkHistoryPageSize = ref(10)
const isLoadingBenchmarkHistory = ref(false)

const presetQuestions = [
  'TOPCon 580W 的电气参数是什么？',
  '出口沙特阿拉伯 SASO 认证有哪些具体要求？',
  '海运集装箱 40HQ 与 20GP 的标准容积与载重是多少？',
  '信用证 UCP600 最迟装运日与提单日期的规则是什么？'
]

// AI 出题评测状态
const selectedDocIdForBenchmark = ref('all')
const benchmarkCount = ref(10)
const isRunningAiBenchmark = ref(false)
const benchmarkReport = ref(null)

// 获取当前配置的模型
const activeModelInfo = computed(() => {
  try {
    const raw = localStorage.getItem('tradeagent_model_config')
    if (raw) {
      const cfg = JSON.parse(raw)
      return `${cfg.modelName || 'qwen-plus'} (${cfg.provider || 'DashScope'})`
    }
  } catch (_) {}
  return '通义千问 Qwen-Plus (默认)'
})

const totalChunksCount = computed(() => {
  return documents.value.reduce((acc, cur) => acc + (cur.chunkCount || 0), 0)
})

const goToModelConfig = () => {
  router.push('/model-config')
}

const goToChatAgent = () => {
  router.push('/chat-agent')
}

// ======================== 阶段四：智能体 Skill 技能中心 ========================
const skillsList = ref([])
const isLoadingSkills = ref(false)
const skillTestModalVisible = ref(false)
const currentTestingSkill = ref(null)
const skillTestParamsText = ref('')
const isExecutingSkill = ref(false)
const skillTestResult = ref(null)

const loadSkillsList = async () => {
  isLoadingSkills.value = true
  try {
    const res = await getSkillsListApi()
    if (res && res.data) {
      skillsList.value = res.data
    }
  } catch (e) {
    ElMessage.error('加载技能列表失败: ' + e.message)
  } finally {
    isLoadingSkills.value = false
  }
}

const handleToggleSkill = async (skill) => {
  try {
    await toggleSkillApi(skill.id)
    ElMessage.success(`技能【${skill.name}】状态已切换为: ${skill.status}`)
  } catch (e) {
    ElMessage.error('切换技能状态失败: ' + e.message)
    loadSkillsList()
  }
}

const openSkillTestModal = (skill) => {
  currentTestingSkill.value = skill
  skillTestParamsText.value = JSON.stringify(skill.sampleInput || {}, null, 2)
  skillTestResult.value = null
  skillTestModalVisible.value = true
}

const handleExecuteSkill = async () => {
  if (!currentTestingSkill.value) return
  isExecutingSkill.value = true
  skillTestResult.value = null
  try {
    let params = {}
    try {
      params = JSON.parse(skillTestParamsText.value || '{}')
    } catch (err) {
      ElMessage.error('输入参数 JSON 格式不合法')
      isExecutingSkill.value = false
      return
    }

    const res = await executeSkillApi(currentTestingSkill.value.id, params)
    if (res && res.data) {
      skillTestResult.value = res.data
      ElMessage.success(`技能单测成功！耗时: ${res.data.totalLatencyMs}ms`)
      loadSkillsList()
    }
  } catch (e) {
    ElMessage.error('技能运行异常: ' + (e.message || '执行失败'))
  } finally {
    isExecutingSkill.value = false
  }
}


// 加载知识库文档列表
const loadDocuments = async () => {
  isLoadingDocs.value = true
  try {
    const res = await getKnowledgeDocumentsApi()
    if (res && res.data) {
      documents.value = res.data
    }
  } catch (err) {
    ElMessage.error('加载知识库文档列表失败: ' + (err.message || '网络异常'))
  } finally {
    isLoadingDocs.value = false
  }
}

// 触发文件选择
const triggerKbFileInput = () => {
  if (kbFileInputRef.value) {
    kbFileInputRef.value.click()
  }
}

// 处理文档上传入库
const handleKbFileSelected = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  e.target.value = ''

  isUploadingDoc.value = true
  try {
    ElMessage.info(`正在解析《${file.name}》执行 AST 结构化切片并持久化入库...`)
    const res = await uploadKnowledgeDocumentApi(file)
    if (res && res.data) {
      ElMessage.success(`《${file.name}》入库成功！切分出 ${res.data.chunkCount} 个切片并已建立倒排索引`)
      await loadDocuments()
    }
  } catch (err) {
    ElMessage.error('文档入库失败: ' + (err.message || '网络异常'))
  } finally {
    isUploadingDoc.value = false
  }
}

// 查看切片抽屉
const viewDocChunks = async (doc) => {
  currentInspectingDoc.value = doc
  chunkDrawerVisible.value = true
  isLoadingChunks.value = true
  try {
    const res = await getDocumentChunksApi(doc.id)
    if (res && res.data) {
      inspectingChunks.value = res.data
    }
  } catch (err) {
    ElMessage.error('获取切片失败: ' + (err.message || '网络异常'))
  } finally {
    isLoadingChunks.value = false
  }
}

// 删除文档
const handleDeleteDoc = async (doc) => {
  try {
    await ElMessageBox.confirm(`确定要从知识库中删除《${doc.name}》及其所有切片吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消'
    })
    const res = await deleteKnowledgeDocumentApi(doc.id)
    if (res && res.data) {
      ElMessage.success(`《${doc.name}》已成功删除`)
      await loadDocuments()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除文档失败: ' + (e.message || '网络异常'))
    }
  }
}

// 从文档列表直接发起在线问答
const startQaWithDoc = (doc) => {
  selectedDocIdForQa.value = doc.id
  activeTab.value = 'online-qa'
}

// 从文档列表直接发起 AI 出题评测
const startBenchmarkWithDoc = (doc) => {
  selectedDocIdForBenchmark.value = doc.id
  activeTab.value = 'benchmark'
}

// 快速填入推荐问题
const usePresetQuestion = (q) => {
  qaInputText.value = q
}

// 获取配置的 API 密钥信息
const getSavedLlmConfig = () => {
  try {
    const raw = localStorage.getItem('tradeagent_model_config')
    if (raw) {
      const cfg = JSON.parse(raw)
      return {
        apiKey: cfg.apiKey || '',
        baseUrl: cfg.baseUrl || '',
        modelName: cfg.modelName || 'qwen-plus'
      }
    }
  } catch (_) {}
  return { apiKey: '', baseUrl: '', modelName: 'qwen-plus' }
}

// 执行在线 RAG 问答
const handleSendQa = async () => {
  const q = qaInputText.value.trim()
  if (!q) {
    ElMessage.warning('请输入提问内容')
    return
  }

  isAskingQa.value = true
  qaResponse.value = null
  currentQaRating.value = null

  try {
    const llmCfg = getSavedLlmConfig()
    const res = await onlineKnowledgeAskApi({
      question: q,
      docId: selectedDocIdForQa.value,
      topK: qaTopK.value,
      apiKey: llmCfg.apiKey,
      baseUrl: llmCfg.baseUrl,
      modelName: llmCfg.modelName
    })

    if (res && res.data) {
      qaResponse.value = res.data
      loadQaHistory()
      ElMessage.success('问答完成，已召回参考切片并生成回答！')
    }
  } catch (err) {
    ElMessage.error('问答失败: ' + (err.message || '网络异常'))
  } finally {
    isAskingQa.value = false
  }
}

// 执行真实 AI 大模型出题评测
const handleRunAiBenchmark = async () => {
  isRunningAiBenchmark.value = true
  benchmarkReport.value = null

  try {
    const llmCfg = getSavedLlmConfig()
    ElMessage.info(`正在调用大模型阅读切片并生成 ${benchmarkCount.value} 道考题与黄金答案进行真实倒排检索检验...`)

    const res = await runAiSyntheticBenchmarkApi({
      docId: selectedDocIdForBenchmark.value,
      count: benchmarkCount.value,
      apiKey: llmCfg.apiKey,
      baseUrl: llmCfg.baseUrl,
      modelName: llmCfg.modelName
    })

    if (res && res.data) {
      benchmarkReport.value = res.data
      loadBenchmarkHistory()
      ElMessage.success(`AI 评测完成！召回率: ${res.data.recallRate}%，对抗防御率: ${res.data.adversarialRejectionRate}%`)
    }
  } catch (err) {
    ElMessage.error('AI 出题评测失败: ' + (err.message || '网络异常'))
  } finally {
    isRunningAiBenchmark.value = false
  }
}

// 评测报告历史列表加载
const loadBenchmarkHistory = async () => {
  isLoadingBenchmarkHistory.value = true
  try {
    const res = await getBenchmarkReportsPageApi({
      pageNum: benchmarkHistoryPageNum.value,
      pageSize: benchmarkHistoryPageSize.value,
      docId: selectedDocIdForBenchmark.value === 'all' ? undefined : selectedDocIdForBenchmark.value
    })
    if (res && res.data) {
      benchmarkHistoryRecords.value = res.data.records || []
      benchmarkHistoryTotal.value = res.data.total || 0
    }
  } catch (err) {
    // 降级不阻断
  } finally {
    isLoadingBenchmarkHistory.value = false
  }
}

const openBenchmarkHistoryDrawer = () => {
  benchmarkHistoryDrawerVisible.value = true
  loadBenchmarkHistory()
}

// 载入某次历史评测的完整报告
const handleViewHistoricalBenchmark = async (record) => {
  try {
    const res = await getBenchmarkReportDetailApi(record.id)
    if (res && res.data) {
      benchmarkReport.value = res.data
      benchmarkHistoryDrawerVisible.value = false
      ElMessage.success(`已载入历史评测报告: ${record.id} (召回率: ${record.recallRate}%)`)
    }
  } catch (err) {
    ElMessage.error('获取历史报告详情失败: ' + (err.message || '网络异常'))
  }
}

// 删除历史评测报告
const handleDeleteHistoricalBenchmark = async (record) => {
  try {
    await ElMessageBox.confirm(`确定删除评测任务 ${record.id} 吗？`, '提示', {
      type: 'warning'
    })
    await deleteBenchmarkReportApi(record.id)
    ElMessage.success('已删除历史评测报告')
    loadBenchmarkHistory()
    if (benchmarkReport.value?.benchmarkId === record.id) {
      benchmarkReport.value = null
    }
  } catch (_) {}
}

// 加载在线问答历史
const loadQaHistory = async () => {
  isLoadingQaHistory.value = true
  try {
    const res = await getQaRecordsPageApi({
      pageNum: qaHistoryPageNum.value,
      pageSize: qaHistoryPageSize.value,
      keyword: qaHistoryKeyword.value || undefined
    })
    if (res && res.data) {
      qaHistoryRecords.value = res.data.records || []
      qaHistoryTotal.value = res.data.total || 0
    }
  } catch (err) {
    // 降级不阻断
  } finally {
    isLoadingQaHistory.value = false
  }
}

const openQaHistoryDrawer = () => {
  qaHistoryDrawerVisible.value = true
  loadQaHistory()
}

// 标记问答人工评价 (准确率审计)
const handleRateQa = async (qaId, rating) => {
  if (!qaId) return
  try {
    await rateQaRecordApi(qaId, { rating })
    currentQaRating.value = rating
    ElMessage.success(rating === 'ACCURATE' ? '已标记为准确 👍' : '已标记为存在偏差 👎')
    loadQaHistory()
  } catch (err) {
    ElMessage.error('打分失败: ' + (err.message || '网络异常'))
  }
}

// 删除单条问答记录
const handleDeleteQaRecord = async (record) => {
  try {
    await ElMessageBox.confirm('确定删除该条问答记录吗？', '提示', { type: 'warning' })
    await deleteQaRecordApi(record.id)
    ElMessage.success('已删除问答记录')
    loadQaHistory()
  } catch (_) {}
}

// 载入历史问答到主视图
const handleInspectHistoricalQa = (record) => {
  let citations = []
  try {
    if (record.citationsJson) {
      citations = JSON.parse(record.citationsJson)
    }
  } catch (_) {}
  qaResponse.value = {
    id: record.id,
    question: record.question,
    answer: record.answer,
    citations: citations,
    latencyMs: record.latencyMs,
    modelUsed: record.modelUsed
  }
  currentQaRating.value = record.userRating || null
  qaHistoryDrawerVisible.value = false
  ElMessage.success('已将历史问答加载至主视图')
}

// 四象限表格数据映射
const quadrantTableData = computed(() => {
  if (!benchmarkReport.value || !benchmarkReport.value.categoryMetrics) return []
  const cm = benchmarkReport.value.categoryMetrics
  return Object.keys(cm).map((key) => {
    const item = cm[key]
    return {
      category: item.categoryName || key,
      total: item.total,
      recall: item.hitRate,
      badCases: item.badCases
    }
  })
})

const formatFileSize = (bytes) => {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
}

// 检查实际召回切片是否命中考题依据切片
const isRecalledMatch = (row, chunkId) => {
  if (!row || !chunkId || !row.expectedChunkId) return false
  if (row.expectedChunkId === 'NONE') return false
  const expectedList = String(row.expectedChunkId).split(/[,，\s]+/).map(s => s.trim()).filter(Boolean)
  return expectedList.includes(String(chunkId).trim())
}

onMounted(async () => {
  loadDocuments()
  loadBenchmarkHistory()
  loadQaHistory()
  loadSkillsList()
  try {
    const res = await getLatestBenchmarkReportApi()
    if (res && res.data) {
      benchmarkReport.value = res.data
    }
  } catch (_) {}
})
</script>

<style scoped>
.workbench-container {
  padding: 16px;
  background-color: #f0f2f5;
  min-height: calc(100vh - 84px);
}

.welcome-card {
  margin-bottom: 16px;
  border-radius: 8px;
  border: 1px solid #e4e7ed;
}

.welcome-box {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}

.user-greeting {
  display: flex;
  align-items: center;
  gap: 16px;
}

.avatar-large {
  background-color: #409eff;
  font-size: 22px;
  font-weight: bold;
}

.greeting-text h3 {
  margin: 0 0 6px 0;
  font-size: 19px;
  color: #303133;
}

.greeting-text p {
  margin: 0;
  font-size: 13px;
  color: #909399;
}

.quick-nav-btns {
  display: flex;
  gap: 12px;
}

.rag-workbench-card {
  border-radius: 8px;
  border: 1px solid #e4e7ed;
  background: #ffffff;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}

.tab-badge {
  margin-left: 4px;
}

.kb-action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}

.action-left {
  display: flex;
  gap: 10px;
}

.kb-summary-stat {
  font-size: 13px;
  color: #606266;
}

.doc-table {
  width: 100%;
}

.doc-name-cell {
  display: flex;
  align-items: center;
}

.doc-name-text {
  font-weight: 500;
  color: #303133;
}

/* 在线问答模块样式 */
.qa-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.qa-control-panel {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
}

.control-row {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.control-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.control-label {
  font-size: 13px;
  font-weight: 500;
  color: #475569;
}

.quick-questions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.quick-label {
  font-size: 12px;
  color: #64748b;
}

.quick-tag {
  cursor: pointer;
  transition: all 0.2s;
}

.quick-tag:hover {
  background-color: #ecf5ff;
  border-color: #409eff;
  color: #409eff;
}

.qa-input-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.qa-submit-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.submit-tip {
  font-size: 12px;
  color: #94a3b8;
}

.qa-result-panel {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
}

.qa-answer-card {
  background: #f0f9eb;
  border-left: 4px solid #67c23a;
  border-radius: 4px;
  padding: 14px 16px;
  margin-bottom: 18px;
}

.answer-text {
  font-size: 14px;
  line-height: 1.7;
  color: #2d3748;
  white-space: pre-wrap;
}

.qa-citations-section {
  margin-top: 12px;
}

.citation-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #334155;
  margin-bottom: 12px;
}

.citation-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 12px;
}

.citation-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.cite-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cite-doc {
  font-size: 12px;
  color: #475569;
  max-width: 140px;
}

.cite-breadcrumb {
  font-size: 12px;
  color: #0284c7;
}

.cite-content {
  font-size: 12px;
  line-height: 1.5;
  color: #475569;
  background: #ffffff;
  padding: 8px;
  border-radius: 4px;
  border: 1px solid #edf2f7;
  max-height: 120px;
  overflow-y: auto;
}

/* 评测模块样式 */
.benchmark-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.benchmark-control-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px 16px;
  flex-wrap: wrap;
  gap: 12px;
}

.benchmark-config-left {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.config-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.config-label {
  font-size: 13px;
  font-weight: 500;
  color: #475569;
}

.benchmark-tip-box {
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.6;
  color: #409eff;
}

.metric-row {
  margin-bottom: 16px;
}

.metric-card {
  padding: 14px;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 84px;
  border: 1px solid transparent;
}

.card-recall {
  background-color: #f0f9eb;
  border-color: #e1f3d8;
}

.card-badcase {
  background-color: #fef0f0;
  border-color: #fde2e2;
}

.card-defense {
  background-color: #ecf5ff;
  border-color: #d9ecff;
}

.card-latency {
  background-color: #fdf6ec;
  border-color: #faecd8;
}

.metric-label {
  font-size: 12px;
  color: #606266;
  margin-bottom: 4px;
}

.metric-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}

.metric-sub {
  font-size: 11px;
  color: #909399;
  margin-top: 4px;
}

.detail-row {
  margin-bottom: 16px;
}

.sub-panel {
  background-color: #ffffff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px 14px;
  min-height: 240px;
}

.sub-panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}

.badcase-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 220px;
  overflow-y: auto;
}

.badcase-item {
  background-color: #fef0f0;
  border: 1px solid #fde2e2;
  border-radius: 6px;
  padding: 10px;
}

.badcase-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.badcase-q {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.badcase-body p {
  margin: 4px 0 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: #606266;
}

.strategy-text {
  color: #409eff;
  font-weight: 500;
}

.empty-badcase {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 0;
  color: #67c23a;
  font-size: 14px;
}

.ai-questions-detail-panel {
  background: #ffffff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 14px;
  margin-top: 14px;
}

.recalled-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.recalled-chip {
  margin: 0 !important;
  font-size: 11px;
}

.recalled-tag {
  display: inline-block;
  background: #f1f5f9;
  border: 1px solid #cbd5e1;
  border-radius: 3px;
  padding: 1px 5px;
  font-size: 11px;
  margin-right: 4px;
  margin-bottom: 3px;
}

/* 抽屉样式 */
.drawer-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 24px;
}

.inspect-chunk-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 12px;
}

.chunk-card-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.chunk-path {
  font-size: 12px;
  color: #0284c7;
  max-width: 320px;
}

.chunk-chars {
  font-size: 11px;
  color: #94a3b8;
}

.chunk-card-text {
  background: #ffffff;
  border: 1px solid #edf2f7;
  border-radius: 4px;
  padding: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #334155;
  white-space: pre-wrap;
  max-height: 200px;
  overflow-y: auto;
}

.text-truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.font-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
}

.benchmark-running-card {
  background: #f0f9eb;
  border: 1px solid #c2e7b0;
  border-radius: 8px;
  padding: 18px 20px;
  margin-top: 14px;
}

.running-header {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  color: #303133;
  margin-bottom: 8px;
}

.running-desc {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
  margin: 0 0 14px 0;
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

.empty-title {
  font-size: 15px;
  font-weight: 600;
  color: #606266;
  margin-top: 12px;
  margin-bottom: 6px;
}

.empty-desc {
  font-size: 13px;
  color: #909399;
  max-width: 500px;
  line-height: 1.5;
}

.qa-btn-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.qa-rating-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px dashed #e2e8f0;
}

.rating-tip {
  font-size: 13px;
  color: #64748b;
  font-weight: 500;
}

.rating-feedback-status {
  font-size: 13px;
  font-weight: 600;
  color: #10b981;
  margin-left: 6px;
}

.divider-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 历史抽屉样式 */
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

.history-search-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.history-stats-badge {
  font-size: 13px;
  color: #64748b;
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

.text-danger {
  color: #ef4444;
  font-weight: 600;
}

.qa-history-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px;
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.qa-history-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.qa-history-q {
  font-size: 14px;
  color: #1e293b;
  line-height: 1.4;
}

.qa-history-answer {
  background: #f8fafc;
  border: 1px solid #f1f5f9;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 13px;
  color: #334155;
  line-height: 1.6;
  white-space: pre-wrap;
  max-height: 180px;
  overflow-y: auto;
}

.qa-history-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: #64748b;
  padding-top: 4px;
}

.qa-history-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
}

/* ==================== 阶段四：意图路由 200 题基准评测样式 ==================== */
.intent-benchmark-container {
  padding: 8px 0;
}

.confusion-matrix-box {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
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

/* ==================== 阶段五：智能体 Skill 技能中心样式 ==================== */
.skills-container {
  padding: 8px 0;
}

.skills-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(135deg, #eff6ff 0%, #e0f2fe 100%);
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  padding: 16px 20px;
}

.skills-banner-text h3 {
  margin: 0 0 6px 0;
  color: #1e3a8a;
  font-size: 16px;
}

.skills-banner-text p {
  margin: 0;
  color: #3b82f6;
  font-size: 13px;
  line-height: 1.5;
}

.skills-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
}

.skill-card {
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  transition: all 0.2s ease;
}

.skill-card.is-active {
  border-color: #93c5fd;
  box-shadow: 0 4px 6px -1px rgba(59, 130, 246, 0.1);
}

.skill-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.skill-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.skill-icon {
  color: #3b82f6;
}

.skill-name {
  margin: 0 0 4px 0;
  font-size: 15px;
  color: #1e293b;
}

.skill-desc {
  font-size: 13px;
  color: #64748b;
  line-height: 1.6;
  margin: 10px 0;
  min-height: 42px;
}

.skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.skill-bound-tools {
  background: #f8fafc;
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 12px;
}

.skill-bound-tools .sub-label {
  font-size: 12px;
  color: #475569;
  margin-bottom: 6px;
}

.tool-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.skill-telemetry-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #64748b;
  border-top: 1px dashed #e2e8f0;
  padding-top: 10px;
  margin-bottom: 10px;
}

.skill-card-footer {
  display: flex;
  justify-content: flex-end;
}

.skill-test-header-meta {
  display: flex;
  align-items: center;
}

.skill-result-box {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 12px;
}

.result-header {
  display: flex;
  align-items: center;
}

.result-code {
  background: #0f172a;
  color: #38bdf8;
  padding: 10px 14px;
  border-radius: 6px;
  font-size: 12px;
  max-height: 240px;
  overflow-y: auto;
  white-space: pre-wrap;
}

.markdown-body {
  line-height: 1.6;
  font-size: 14px;
  color: #303133;
  word-break: break-word;

  p {
    margin-bottom: 8px;
    &:last-child {
      margin-bottom: 0;
    }
  }

  strong {
    color: #1e293b;
    font-weight: 600;
  }

  code {
    background: #f1f5f9;
    padding: 2px 6px;
    border-radius: 4px;
    font-family: monospace;
    font-size: 13px;
    color: #e11d48;
  }

  pre {
    background: #1e293b;
    color: #f8fafc;
    padding: 12px;
    border-radius: 6px;
    overflow-x: auto;
    font-family: monospace;
    font-size: 13px;
    margin: 8px 0;
  }

  ul, ol {
    padding-left: 20px;
    margin-bottom: 8px;
  }

  table {
    border-collapse: collapse;
    width: 100%;
    margin: 8px 0;

    th, td {
      border: 1px solid #e2e8f0;
      padding: 6px 12px;
      text-align: left;
    }

    th {
      background: #f8fafc;
      font-weight: 600;
    }
  }
}
</style>

