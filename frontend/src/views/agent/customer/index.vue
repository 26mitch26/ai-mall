<template>
  <div class="agent-page customer-page">
    <section class="hero-panel">
      <div>
        <div class="eyebrow">LOCAL RAG COPILOT</div>
        <h1>智能客服 Agent</h1>
        <p>本地智能客服 · 可追溯政策依据 · 售后提交前明确确认</p>
      </div>
      <div class="hero-actions">
        <el-tag :type="connected ? 'success' : 'danger'" effect="dark" round>
          {{ connected ? '服务在线' : '服务离线' }}
        </el-tag>
        <el-button :icon="Refresh" circle @click="loadStatus" />
        <el-button type="primary" :icon="UploadFilled" @click="knowledgeVisible = true">导入知识</el-button>
        <el-button v-if="!memberToken" plain @click="memberLoginVisible = true">会员登录</el-button>
        <el-button v-else plain @click="logoutMember">{{ memberUsername || '会员' }} · 退出</el-button>
      </div>
    </section>

    <section class="metric-grid">
      <div class="metric-card violet">
        <span class="metric-label">生成模型</span>
        <strong>{{ ragStatus?.chatModel || '状态未知' }}</strong>
        <small>以服务状态接口为准</small>
      </div>
      <div class="metric-card cyan">
        <span class="metric-label">Embedding</span>
        <strong>{{ ragStatus?.embeddingModel || '状态未知' }}</strong>
        <small>Embedding 服务状态</small>
      </div>
      <div class="metric-card blue">
        <span class="metric-label">向量检索</span>
        <strong>{{ ragStatus ? `${ragStatus.vectorStore} · ${ragStatus.vectorIndex}` : '状态未知' }}</strong>
        <small>按问题选择语义、关键词或混合检索</small>
      </div>
      <div class="metric-card green">
        <span class="metric-label">最近响应</span>
        <strong>{{ lastLatency ? `${lastLatency} ms` : '等待提问' }}</strong>
        <small>语义缓存已启用</small>
      </div>
    </section>

    <section class="workspace-grid">
      <aside class="explain-panel">
        <div class="panel-title">检索链路设计 · 本轮路径见回答</div>
        <div class="pipeline">
          <div v-for="(step, index) in pipeline" :key="step" class="pipeline-step">
            <span>{{ String(index + 1).padStart(2, '0') }}</span>
            <div>
              <strong>{{ step }}</strong>
              <small>{{ pipelineNotes[index] }}</small>
            </div>
          </div>
        </div>

        <div class="panel-title question-title">演示问题</div>
        <button v-for="question in quickQuestions" :key="question" class="question-chip" @click="ask(question)">
          {{ question }}
        </button>

        <div class="architecture-note">
          <el-icon><Connection /></el-icon>
          <div>
            <strong>RAG ≠ ANN</strong>
            <p>ANN 只是向量召回环节；完整链路还包含 BM25、RRF、重排和 LLM 生成。</p>
          </div>
        </div>

        <div class="panel-title collaboration-title">多 Agent 协作</div>
        <div class="collaboration-route">
          <span class="route-dot"></span>
          <span>{{ collaboration?.routeSummary || '本轮路由信息尚未返回' }}</span>
        </div>
        <div v-if="visibleAgents.length" class="agent-team">
          <div v-for="agent in visibleAgents" :key="agent.id" :class="['agent-node', { active: agent.active }]">
            <div class="agent-node-icon">{{ agent.id === 'router' ? 'R' : agent.id === 'coordinator' ? 'Σ' : 'A' }}</div>
            <div class="agent-node-copy">
              <strong>{{ agent.name }}</strong>
              <small>{{ agent.description }}</small>
            </div>
            <el-tag size="small" :type="agent.active ? 'success' : 'info'" effect="plain">
              {{ agent.active ? '本轮参与' : '待命' }}
            </el-tag>
          </div>
        </div>
        <div v-else class="collaboration-empty">等待服务端返回本轮协作明细</div>
      </aside>

      <main class="chat-panel">
        <header class="chat-header">
          <div>
            <strong>AI-Mall 服务助手</strong>
            <span>会话 {{ sessionId.slice(-8) }}</span>
          </div>
          <div class="status-dots">
            <span :class="{ online: ragStatus?.ollamaOnline }"></span> Ollama
            <span :class="{ online: connected }"></span> RAG
          </div>
        </header>

        <div class="task-strip">
          <div class="task-caption">
            <strong>任务型对话</strong>
            <small>DST 槽位 · DPM 决策 · API 执行</small>
          </div>
          <div class="task-actions">
            <el-button size="small" :loading="taskLoading" @click="runTask('搜索商品 手机')">搜索商品</el-button>
            <el-button size="small" :loading="taskLoading" @click="runTask('查订单')">查订单</el-button>
            <el-tag v-if="memberToken" size="small" type="success" effect="plain">会员登录态 · 请求逐次核验</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">访客模式</el-tag>
          </div>
        </div>
        <div v-if="taskResult" class="task-result">
          <div class="task-result-head">
            <span><strong>{{ taskResult.taskName }}</strong> · {{ taskResult.dpmAction }}</span>
            <el-tag size="small" :type="taskResult.completed ? 'success' : taskResult.dpmAction === 'ASK_SLOT' ? 'warning' : 'info'" effect="plain">
              {{ taskResult.completed ? '已完成' : taskResult.dpmAction === 'ASK_SLOT' ? '等待补充' : '待处理' }}
            </el-tag>
          </div>
          <p>{{ taskResult.nextPrompt }}</p>
          <small v-if="taskResult.apiCall">{{ taskResult.apiCall }} · 已填槽位 {{ Object.keys(taskResult.dstState.slots || {}).join('、') || '无' }}</small>
        </div>

        <details class="customer-tools">
          <summary>售后工单与照片辅助</summary>
          <div class="tools-scroll">
        <section class="workflow-panel">
          <div class="workflow-heading">
            <div><strong>售后申请草稿</strong><small>先核对订单与政策，再由你明确确认提交</small></div>
            <el-button v-if="storedWorkflowTaskId" size="small" :loading="workflowLoading" @click="recoverWorkflow">恢复 / 查询状态</el-button>
          </div>
          <div class="workflow-form">
            <el-input v-model="afterSaleForm.orderSn" placeholder="订单号" :disabled="workflowLoading" />
            <el-input v-model="afterSaleForm.reason" placeholder="申请原因" :disabled="workflowLoading" />
            <el-input v-model="afterSaleForm.description" placeholder="情况说明" :disabled="workflowLoading" />
            <el-button type="primary" size="small" :loading="workflowLoading" :disabled="!memberToken" @click="prepareAfterSale">生成草稿</el-button>
          </div>
          <el-alert v-if="!memberToken" title="登录会员账号后才能核验本人订单并保存售后草稿。" type="info" :closable="false" />
          <div v-if="workflowState" class="workflow-state">
            <div class="workflow-state-head">
              <el-tag :type="workflowStatusType(workflowState.status)" effect="plain">{{ workflowState.status }}</el-tag>
              <span>任务 {{ workflowState.taskId }} · 版本 {{ workflowState.version }}</span>
            </div>
            <p><strong>冻结订单信息</strong></p>
            <pre>{{ prettyJson(workflowState.orderSummary) }}</pre>
            <p><strong>申请理由：</strong>{{ workflowState.reason }}</p>
            <p><strong>政策参考</strong></p>
            <pre>{{ workflowState.policyAnswer }}</pre>
            <p><strong>草稿</strong></p>
            <pre>{{ workflowState.draft }}</pre>
            <el-alert v-if="workflowState.status === 'UNKNOWN'" title="提交结果待核对。请查询状态或人工核查，不要盲目重试。" type="warning" :closable="false" />
            <div v-if="workflowState.status === 'WAITING_CONFIRMATION'" class="workflow-confirm-actions">
              <el-button type="danger" plain :loading="workflowLoading" @click="decideAfterSale(false)">拒绝并关闭草稿</el-button>
              <el-button type="primary" :loading="workflowLoading" @click="decideAfterSale(true)">我已核对，确认提交</el-button>
            </div>
            <p v-if="workflowState.result" class="workflow-result">{{ workflowState.result }}</p>
          </div>
        </section>

        <section class="vision-panel">
          <div class="workflow-heading"><div><strong>售后照片辅助识别</strong><small>仅生成观察结果和草稿理由，不会创建申请</small></div></div>
          <div class="vision-form">
            <input type="file" accept="image/*" :disabled="visionLoading" @change="selectVisionFile" />
            <el-button size="small" :loading="visionLoading" :disabled="!visionFile" @click="inspectPhoto">分析照片</el-button>
          </div>
          <div v-if="visionResult" class="vision-result">
            <el-tag size="small" effect="plain">{{ visionResult.model }} · {{ visionResult.elapsedMs }} ms</el-tag>
            <ul class="vision-observations"><li v-for="(observation, index) in visionResult.observations" :key="`${index}-${observation}`">{{ observation }}</li></ul>
            <p>建议理由：{{ visionResult.draftReason }}</p>
            <el-alert :title="visionResult.requiresReview ? '需要人工复核；识别内容仅作建议，不会自动创建售后申请。' : '识别结果仅作草稿参考，不会自动创建售后申请。请人工核对。'" type="warning" :closable="false" />
          </div>
        </section>

          </div>
        </details>
        <ChatModelPicker v-model="selectedModelConfig" :disabled="loading" />
        <div ref="messageListRef" class="message-list">
          <div v-for="(message, index) in messages" :key="index" :class="['message-row', message.role]">
            <div class="avatar">
              <el-icon><User v-if="message.role === 'user'" /><Service v-else /></el-icon>
            </div>
            <div class="bubble-wrap">
              <div class="bubble">{{ message.content }}</div>
              <div v-if="message.modelInfo" class="message-meta">{{ message.modelInfo }}</div>
              <div v-if="message.latency" class="message-meta">
                {{ message.intent || 'general' }} · {{ message.latency }} ms · {{ message.retrievalRoute || '路由未返回' }}
              </div>
              <details v-if="message.trace || message.evidenceReport" class="trace-details">
                <summary>查看检索、阶段耗时与证据核验</summary>
                <div class="trace-panel">
                <div class="trace-summary">
                  <span v-if="message.retrievalDecision">检索判断：{{ message.retrievalDecision }}</span>
                  <span v-if="typeof message.evidenceScore === 'number'">证据分值：{{ message.evidenceScore.toFixed(3) }}</span>
                  <span>重排：{{ message.reranker || '未返回' }}</span>
                  <span>纠错次数：{{ message.correctionCount ?? '未返回' }}</span>
                  <span>知识版本：{{ message.knowledgeVersion || '未返回' }}</span>
                  <span v-if="message.trace">模型调用 {{ message.trace.modelCalls }} · 输入 {{ message.trace.inputTokens }} tokens · 输出 {{ message.trace.outputTokens }} tokens</span>
                  <span v-if="message.trace?.traceId">Trace {{ message.trace.traceId }}</span>
                </div>
                <div v-if="message.trace?.stages?.length" class="trace-stages">
                  <span v-for="stage in message.trace.stages" :key="`${stage.name}-${stage.durationMs}`">{{ stage.name }} · {{ stage.durationMs }} ms · {{ stage.outcome }}</span>
                </div>
                <div v-if="message.evidenceReport" class="evidence-report">
                  <span>证据校验：{{ message.evidenceReport.method }} · 检查 {{ message.evidenceReport.checkedClaims }} 项 · 未支持数字项 {{ message.evidenceReport.unsupportedNumericClaims }} 项</span>
                  <span v-for="(claim, claimIndex) in message.evidenceReport.claims" :key="`${claimIndex}-${claim.text}`">{{ claim.text }} · {{ claim.support }} · 证据 {{ claim.evidenceIds.join(', ') || '无' }}</span>
                  <el-alert title="词法证据校验仅检查词项和数字线索，不构成语义支持保证。请对照下方原文判断。" type="warning" :closable="false" />
                  <el-alert v-if="message.evidenceReport.conflicts?.length" title="检索证据中检测到冲突线索" :description="message.evidenceReport.conflicts.join('；')" type="warning" :closable="false" />
                </div>
              </div>
              </details>
              <details v-if="message.graph?.status === 'ready' && message.graph.paths.length" class="graph-evidence">
                <summary>政策主题关联（局部图检索）</summary>
                <p>Neo4j 按共享主题扩展公开政策检索；关联边用于定位证据，并不等同于完整 Microsoft GraphRAG。</p>
                <p v-if="message.graph.retrievalHints.length">检索提示：{{ message.graph.retrievalHints.join('、') }}</p>
                <ul><li v-for="(path, pathIndex) in message.graph.paths" :key="`${pathIndex}-${path.from}-${path.to}`">{{ path.from }} v{{ path.fromVersion }} —{{ path.sharedTopic }}→ {{ path.to }} v{{ path.toVersion }}</li></ul>
              </details>
              <div v-if="message.sources?.length" class="source-list">
                <button v-for="source in message.sources" :key="source.id" class="source-card" @click="openSource(source)">
                  <el-icon><DocumentIcon /></el-icon>
                  <span><strong>{{ source.source }}</strong><small :title="source.version">{{ source.type }} · {{ source.retrievalSource }} · 排序 {{ source.score.toFixed(2) }} · v{{ source.version?.slice(0, 12) || '未知' }} · {{ source.scope || '范围未知' }}</small></span>
                  <el-tag size="small" :type="source.evidenceVerified ? 'success' : 'info'" effect="plain">{{ source.evidenceVerified ? '引用版本已验证' : '版本未验证' }}</el-tag>
                  <small>{{ source.contentKind === 'selected-excerpt' ? '本次引用片段' : '来源预览' }}</small>
                  <el-icon class="source-arrow"><ArrowRight /></el-icon>
                </button>
              </div>
            </div>
          </div>

          <div v-if="loading" class="message-row assistant">
            <div class="avatar"><el-icon><Service /></el-icon></div>
            <div class="bubble typing">
              <span></span><span></span><span></span>
              <em>正在执行混合检索与本地生成</em>
            </div>
          </div>
        </div>

        <div class="composer">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="例如：商品签收后几天内可以申请退货？"
            :disabled="loading"
            @keydown.enter.exact.prevent="sendMessage"
          />
          <div class="composer-footer">
            <span>Enter 发送 · 检索与证据核验结果以服务端返回为准</span>
            <el-button type="primary" :icon="Promotion" :loading="loading" @click="sendMessage">发送</el-button>
          </div>
        </div>
      </main>
    </section>

    <el-dialog v-model="sourceVisible" title="回答依据" width="720px">
      <div v-if="selectedSource" class="source-dialog">
        <div class="source-dialog-head">
          <el-icon><DocumentIcon /></el-icon>
        <div>
          <strong>{{ selectedSource.source }}</strong>
          <span>{{ selectedSource.type }} · {{ selectedSource.retrievalSource }} · 排序分值 {{ selectedSource.score.toFixed(2) }}</span>
          <span>版本 {{ selectedSource.version || '未知' }} · 生效时间 {{ selectedSource.effectiveAt || '未提供' }} · 范围 {{ selectedSource.scope || '未提供' }}</span>
          <span>内容哈希 {{ selectedSource.contentHash || '未提供' }} · 引用版本 {{ selectedSource.evidenceVerified ? '已验证' : '未验证' }}</span>
        </div>
        </div>
        <div class="source-dialog-toolbar">
          <span>{{ sourceFullContent ? '知识库原文全文' : selectedSource.contentKind === 'selected-excerpt' ? '本次引用片段' : '来源预览' }}</span>
          <el-button link type="primary" :loading="sourceLoading" @click="loadFullSource(selectedSource)">重新加载全文</el-button>
        </div>
        <el-scrollbar max-height="430px" class="source-fulltext">
          <pre>{{ sourceFullContent || selectedSource.content }}</pre>
        </el-scrollbar>
        <el-alert v-if="sourceNotFound" title="未找到全文索引，当前显示本次命中的原文片段。" type="warning" :closable="false" show-icon />
      </div>
    </el-dialog>

    <el-dialog v-model="memberLoginVisible" title="会员登录" width="420px">
      <el-form label-position="top" @submit.prevent="loginMember">
        <el-form-item label="用户名"><el-input v-model="memberLoginForm.username" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="memberLoginForm.password" type="password" show-password autocomplete="current-password" @keyup.enter="loginMember" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberLoginVisible = false">取消</el-button>
        <el-button type="primary" :loading="memberLoginLoading" @click="loginMember">登录</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="knowledgeVisible" title="导入本地知识库" width="620px">
      <el-form label-position="top">
        <el-form-item label="来源名称">
          <el-input v-model="knowledgeForm.source" placeholder="例如：refund-policy.md" />
        </el-form-item>
        <el-form-item label="文档正文">
          <el-input v-model="knowledgeForm.content" type="textarea" :rows="8" placeholder="粘贴退换货、配送、支付等规则" />
        </el-form-item>
        <el-form-item label="分块策略">
          <el-radio-group v-model="knowledgeForm.chunkStrategy">
            <el-radio-button value="sentence">按句分块</el-radio-button>
            <el-radio-button value="fixed_size">固定长度</el-radio-button>
            <el-radio-button value="semantic">语义分块</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="knowledgeVisible = false">取消</el-button>
        <el-button type="primary" :loading="ingesting" @click="ingestKnowledge">写入 Milvus + BM25</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import ChatModelPicker from '@/components/ChatModelPicker.vue'
import type { ChatModelConfig } from '@/apis/agent'
import { ArrowRight, Connection, Document as DocumentIcon, Promotion, Refresh, Service, UploadFilled, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getRagStatusAPI,
  getKnowledgeSourceAPI,
  executeTaskAPI,
  loginMemberAPI,
  prepareAfterSaleAPI,
  confirmAfterSaleAPI,
  getAfterSaleAPI,
  inspectCustomerPhotoAPI,
  ingestKnowledgeAPI,
  sendChatMessageAPI,
  type CollaborationPlan,
  type AfterSaleWorkflowState,
  type RagStatus,
  type SourceReference,
  type TaskResponse,
  type ChatResponse,
  type VisionInspectionResult,
} from '@/apis/agent'

interface ChatMessage {
  modelInfo?: string
  role: 'user' | 'assistant'
  content: string
  latency?: number
  intent?: string
  sources?: SourceReference[]
  retrievalRoute?: string
  retrievalDecision?: string
  evidenceScore?: number
  reranker?: string
  correctionCount?: number
  knowledgeVersion?: string
  trace?: ChatResponse['trace']
  evidenceReport?: ChatResponse['evidenceReport']
  graph?: ChatResponse['graph']
}

const messages = ref<ChatMessage[]>([
  { role: 'assistant', content: '你好，我是 AI-Mall 智能客服。你可以询问退换货、配送、支付或商品使用问题。' },
])
const inputMessage = ref('')
const loading = ref(false)
const selectedModelConfig = ref<ChatModelConfig>()
const ragStatus = ref<RagStatus | null>(null)
const sessionId = ref(`mall_${Date.now()}`)
const messageListRef = ref<HTMLElement | null>(null)
const lastLatency = ref(0)
const knowledgeVisible = ref(false)
const ingesting = ref(false)
const sourceVisible = ref(false)
const selectedSource = ref<SourceReference | null>(null)
const sourceLoading = ref(false)
const sourceNotFound = ref(false)
const sourceFullContent = ref('')
const collaboration = ref<CollaborationPlan | null>(null)
const taskLoading = ref(false)
const taskResult = ref<TaskResponse | null>(null)
const memberToken = ref(sessionStorage.getItem('customerMemberToken') || '')
const memberUsername = ref(sessionStorage.getItem('customerMemberUsername') || '')
const memberLoginVisible = ref(false)
const memberLoginLoading = ref(false)
const memberLoginForm = ref({ username: '', password: '' })
const workflowLoading = ref(false)
const workflowState = ref<AfterSaleWorkflowState | null>(null)
const storedWorkflowTaskId = ref('')
const storedWorkflowSessionId = ref('')
const afterSaleForm = ref({ orderSn: '', reason: '', description: '' })
const visionFile = ref<File | null>(null)
const visionLoading = ref(false)
const visionResult = ref<VisionInspectionResult | null>(null)
const knowledgeForm = ref({
  source: 'course-demo-policy.md',
  content: '',
  chunkStrategy: 'sentence',
  type: 'policy',
})

const pipeline = computed(() => ragStatus.value?.pipeline || [
  'Query Rewrite', 'ANN 语义召回', 'BM25 关键词召回', 'RRF 融合', '特征重排', 'Ollama 生成',
])
const pipelineNotes = ['同义词扩展与问题改写', 'bge-m3 → Milvus', '精确命中规则与编号', '合并两路排名', '相关性特征精排', '本地模型生成答案']
const connected = computed(() => Boolean(ragStatus.value?.online && ragStatus.value?.ollamaOnline))
const visibleAgents = computed(() => collaboration.value?.agents || [])
const quickQuestions = [
  '签收后多久可以申请退货？',
  '订单什么时候免运费？',
  '退款一般多久到账？',
  '如何查询我的订单状态？',
]

const scrollToBottom = () => nextTick(() => {
  if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
})

const loadStatus = async () => {
  try {
    const response = await getRagStatusAPI()
    ragStatus.value = response.data
  } catch {
    ragStatus.value = null
  }
}

const ask = (question: string) => {
  inputMessage.value = question
  sendMessage()
}

const sendMessage = async () => {
  const content = inputMessage.value.trim()
  if (!content || loading.value) return
  messages.value.push({ role: 'user', content })
  inputMessage.value = ''
  loading.value = true
  scrollToBottom()
  try {
    const response = await sendChatMessageAPI({ message: content, sessionId: sessionId.value, modelConfig: selectedModelConfig.value }, memberToken.value)
    sessionId.value = response.data.sessionId
    lastLatency.value = response.data.responseTime
    messages.value.push({
      role: 'assistant',
      content: response.data.answer,
      modelInfo: response.data.generationUsed ? response.data.usedModels?.length ? `模型调用：${response.data.usedModels.join('、')}` : '模型调用未成功' : response.data.selectedModel ? '本条未调用生成模型' : undefined,
      latency: response.data.responseTime,
      intent: response.data.intent,
      sources: response.data.sources || [],
      retrievalRoute: response.data.retrievalRoute,
      retrievalDecision: response.data.retrievalDecision,
      evidenceScore: response.data.evidenceScore,
      reranker: response.data.reranker,
      correctionCount: response.data.correctionCount,
      knowledgeVersion: response.data.knowledgeVersion,
      trace: response.data.trace,
      evidenceReport: response.data.evidenceReport,
      graph: response.data.graph,
    })
    collaboration.value = response.data.collaboration || null
  } catch (error: any) {
    ElMessage.error(`客服 Agent 调用失败：${error?.message || '请检查 8083 服务'}`)
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

const openSource = (source: SourceReference) => {
  selectedSource.value = source
  sourceFullContent.value = ''
  sourceNotFound.value = false
  sourceVisible.value = true
  loadFullSource(source)
}

const loadFullSource = async (source: SourceReference) => {
  sourceLoading.value = true
  sourceNotFound.value = false
  try {
    const response = await getKnowledgeSourceAPI(source.source, source.version)
    if (response.data.found && response.data.content) {
      sourceFullContent.value = response.data.content
    } else {
      sourceNotFound.value = true
    }
  } catch {
    sourceNotFound.value = true
  } finally {
    sourceLoading.value = false
  }
}

const runTask = async (message: string) => {
  taskLoading.value = true
  try {
    const response = await executeTaskAPI({ message, sessionId: sessionId.value }, memberToken.value)
    taskResult.value = response.data
    sessionId.value = response.data.sessionId
  } catch (error: any) {
    ElMessage.error(`任务执行失败：${error?.message || '请检查客服 Agent'}`)
  } finally {
    taskLoading.value = false
  }
}

const loginMember = async () => {
  if (!memberLoginForm.value.username.trim() || !memberLoginForm.value.password) {
    ElMessage.warning('请输入会员用户名和密码')
    return
  }
  memberLoginLoading.value = true
  try {
    const response = await loginMemberAPI(memberLoginForm.value.username.trim(), memberLoginForm.value.password)
    memberToken.value = `${response.data.tokenHead}${response.data.token}`
    memberUsername.value = memberLoginForm.value.username.trim()
    sessionStorage.setItem('customerMemberToken', memberToken.value)
    sessionStorage.setItem('customerMemberUsername', memberUsername.value)
    memberLoginForm.value.password = ''
    memberLoginVisible.value = false
    ElMessage.success('会员登录成功；客服请求将使用会员身份验证')
  } catch (error: any) {
    memberLoginForm.value.password = ''
    ElMessage.error(`会员登录失败：${error?.message || '请检查用户名和密码'}`)
  } finally {
    memberLoginLoading.value = false
  }
}

const clearStoredWorkflow = () => {
  storedWorkflowTaskId.value = ''
  storedWorkflowSessionId.value = ''
  workflowState.value = null
  sessionStorage.removeItem('agent.afterSaleWorkflow')
}

const persistWorkflowRef = (state: AfterSaleWorkflowState) => {
  storedWorkflowTaskId.value = state.taskId
  storedWorkflowSessionId.value = state.sessionId || sessionId.value
  sessionStorage.setItem('agent.afterSaleWorkflow', JSON.stringify({
    taskId: state.taskId,
    version: state.version,
    status: state.status,
    sessionId: storedWorkflowSessionId.value,
  }))
}

const updateWorkflow = (state: AfterSaleWorkflowState) => {
  workflowState.value = state
  persistWorkflowRef(state)
}

const logoutMember = () => {
  memberToken.value = ''
  memberUsername.value = ''
  sessionStorage.removeItem('customerMemberToken')
  sessionStorage.removeItem('customerMemberUsername')
  clearStoredWorkflow()
  ElMessage.info('已退出会员账号')
}

const prepareAfterSale = async () => {
  if (!memberToken.value) {
    memberLoginVisible.value = true
    return
  }
  if (!afterSaleForm.value.orderSn.trim() || !afterSaleForm.value.reason.trim()) {
    ElMessage.warning('请填写订单号和申请理由')
    return
  }
  workflowLoading.value = true
  try {
    const response = await prepareAfterSaleAPI({
      sessionId: sessionId.value,
      orderSn: afterSaleForm.value.orderSn.trim(),
      reason: afterSaleForm.value.reason.trim(),
      description: afterSaleForm.value.description.trim(),
    }, memberToken.value)
    updateWorkflow(response.data)
    ElMessage.success('售后草稿已保存，请核对订单和政策后决定')
  } catch (error: any) {
    ElMessage.error(`生成草稿失败：${error?.message || '请核对登录状态和订单号'}`)
  } finally {
    workflowLoading.value = false
  }
}

const decideAfterSale = async (approved: boolean) => {
  const current = workflowState.value
  if (!current || current.status !== 'WAITING_CONFIRMATION' || !memberToken.value) return
  workflowLoading.value = true
  try {
    const response = await confirmAfterSaleAPI(current.taskId, current.version, approved,
      memberToken.value, storedWorkflowSessionId.value || sessionId.value)
    updateWorkflow(response.data)
    if (response.data.status === 'UNKNOWN') {
      ElMessage.warning('提交结果待核对，请查询状态，不要盲目重试')
    } else if (approved) {
      ElMessage.success(`流程状态：${response.data.status}`)
    } else {
      ElMessage.info('已拒绝并关闭售后草稿')
    }
  } catch (error: any) {
    ElMessage.error(`更新售后流程失败：${error?.message || '请查询当前状态后再操作'}`)
  } finally {
    workflowLoading.value = false
  }
}

const recoverWorkflow = async () => {
  if (!memberToken.value || !storedWorkflowTaskId.value) {
    ElMessage.warning('请先登录创建该售后草稿的会员账号')
    return
  }
  workflowLoading.value = true
  try {
    const response = await getAfterSaleAPI(storedWorkflowTaskId.value, memberToken.value,
      storedWorkflowSessionId.value || sessionId.value)
    updateWorkflow(response.data)
    if (response.data.status === 'UNKNOWN') {
      ElMessage.warning('状态仍待核对；查询不会重发售后提交')
    }
  } catch (error: any) {
    ElMessage.error(`恢复工作流失败：${error?.message || '该草稿可能已过期或属于其他会员'}`)
  } finally {
    workflowLoading.value = false
  }
}

const workflowStatusType = (status: string) => {
  if (status === 'COMPLETED') return 'success'
  if (status === 'WAITING_CONFIRMATION') return 'warning'
  if (status === 'UNKNOWN') return 'danger'
  return 'info'
}

const prettyJson = (value?: string) => {
  if (!value) return '未返回订单快照'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

const selectVisionFile = (event: Event) => {
  const target = event.target as HTMLInputElement
  visionFile.value = target.files?.[0] || null
  visionResult.value = null
}

const inspectPhoto = async () => {
  if (!visionFile.value) return
  visionLoading.value = true
  try {
    const response = await inspectCustomerPhotoAPI(visionFile.value, memberToken.value)
    visionResult.value = response.data
    if (response.data.draftReason) afterSaleForm.value.reason = response.data.draftReason
    ElMessage.info('照片识别仅作草稿建议，请人工核对')
  } catch (error: any) {
    ElMessage.error(`照片识别失败：${error?.message || '请检查本地视觉服务'}`)
  } finally {
    visionLoading.value = false
  }
}

const ingestKnowledge = async () => {
  if (!knowledgeForm.value.content.trim()) {
    ElMessage.warning('请先输入知识正文')
    return
  }
  ingesting.value = true
  try {
    const response = await ingestKnowledgeAPI(knowledgeForm.value)
    ElMessage.success(`知识已入库，文档 ID：${response.data.docId.slice(0, 8)}`)
    knowledgeVisible.value = false
    knowledgeForm.value.content = ''
  } catch (error: any) {
    ElMessage.error(`导入失败：${error?.message || '请检查 Milvus'}`)
  } finally {
    ingesting.value = false
  }
}

onMounted(() => {
  loadStatus()
  try {
    const stored = sessionStorage.getItem('agent.afterSaleWorkflow')
    if (stored) {
      const refData = JSON.parse(stored) as { taskId?: string; sessionId?: string }
      storedWorkflowTaskId.value = refData.taskId || ''
      storedWorkflowSessionId.value = refData.sessionId || ''
    }
  } catch {
    sessionStorage.removeItem('agent.afterSaleWorkflow')
  }
})
</script>

<style scoped>
.agent-page { box-sizing: border-box; display: flex; flex-direction: column; height: calc(100vh - 84px); min-height: 0; padding: 14px 18px; overflow: hidden; background: #f4f7fb; color: #172033; }
.hero-panel { display: flex; justify-content: space-between; gap: 24px; padding: 16px 22px; color: white; border-radius: 15px; background: radial-gradient(circle at 15% 20%, rgba(121,99,255,.65), transparent 30%), linear-gradient(120deg, #151a38, #293681 58%, #155b73); box-shadow: 0 12px 28px rgba(31,45,100,.15); }
.eyebrow { color: #8fe8ff; font-size: 12px; font-weight: 700; letter-spacing: 2px; }
h1 { margin: 4px 0 5px; font-size: 25px; }
.hero-panel p { margin: 0; max-width: 720px; color: #dce6ff; line-height: 1.7; }
.hero-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.metric-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin: 10px 0; }
.metric-card { position: relative; overflow: hidden; padding: 11px 14px; border: 1px solid #e5e9f3; border-radius: 11px; background: white; }
.metric-card::after { content: ''; position: absolute; width: 80px; height: 80px; right: -28px; bottom: -34px; border-radius: 50%; background: currentColor; opacity: .09; }
.metric-card.violet { color: #7655d6; }.metric-card.cyan { color: #168ca6; }.metric-card.blue { color: #3476d9; }.metric-card.green { color: #26966b; }
.metric-label, .metric-card small { display: block; color: #7b8499; font-size: 12px; }
.metric-card strong { display: block; margin: 5px 0 2px; color: #172033; font-size: 15px; }
.workspace-grid { display: grid; grid-template-columns: 292px minmax(0, 1fr); gap: 12px; flex: 1; min-height: 0; }
.explain-panel, .chat-panel { border: 1px solid #e4e9f2; border-radius: 16px; background: white; box-shadow: 0 8px 28px rgba(28,45,92,.06); }
.explain-panel { min-height: 0; padding: 14px; overflow: hidden; }
.panel-title { margin-bottom: 9px; color: #263149; font-size: 13px; font-weight: 700; }
.pipeline { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 4px; }
.pipeline-step { display: flex; align-items: center; gap: 6px; min-width: 0; padding: 6px; border-radius: 8px; }
.pipeline-step:hover { background: #f4f7ff; }
.pipeline-step > span { display: grid; place-items: center; width: 22px; height: 22px; flex: 0 0 auto; border-radius: 6px; color: #4256d0; background: #eef0ff; font-size: 9px; font-weight: 700; }
.pipeline-step strong, .pipeline-step small { display: block; }
.pipeline-step strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; }.pipeline-step small { display: none; }
.question-title { margin-top: 12px; }
.question-chip { width: 100%; margin-bottom: 5px; padding: 7px 9px; color: #47536d; text-align: left; border: 1px solid #e5e9f2; border-radius: 8px; background: #fafbfe; cursor: pointer; font-size: 11px; }
.question-chip:hover { color: #4054cf; border-color: #aeb9ff; background: #f3f5ff; }
.architecture-note { display: flex; gap: 8px; margin-top: 8px; padding: 9px; color: #365d68; border-radius: 9px; background: #ecf9f8; font-size: 11px; }
.architecture-note p { margin: 3px 0 0; font-size: 10px; line-height: 1.4; }
.collaboration-title { margin-top: 12px; margin-bottom: 6px; }
.collaboration-route { display: flex; align-items: center; gap: 7px; margin-bottom: 9px; color: #637096; font-size: 10px; line-height: 1.4; }
.collaboration-empty { padding: 8px; color: #929aab; border: 1px dashed #dfe4ee; border-radius: 7px; font-size: 10px; }
.route-dot { width: 7px; height: 7px; flex: 0 0 auto; border-radius: 50%; background: #35b87f; box-shadow: 0 0 0 4px rgba(53,184,127,.12); }
.agent-team { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px; }
.agent-node { display: flex; align-items: center; gap: 5px; min-width: 0; padding: 5px; border: 1px solid #edf0f6; border-radius: 8px; background: #fbfcff; }
.agent-node.active { border-color: #bfc8ff; background: #f3f5ff; }
.agent-node-icon { display: grid; place-items: center; width: 23px; height: 23px; flex: 0 0 auto; color: #5a68c8; border-radius: 7px; background: #e9edff; font-size: 10px; font-weight: 800; }
.agent-node-copy { min-width: 0; flex: 1; }.agent-node-copy strong, .agent-node-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.agent-node-copy strong { color: #394568; font-size: 9px; }.agent-node-copy small { margin-top: 2px; color: #929aab; font-size: 8px; }
.chat-panel { display: flex; flex-direction: column; min-height: 0; overflow: hidden; }
.chat-header { display: flex; justify-content: space-between; align-items: center; padding: 11px 14px; border-bottom: 1px solid #edf0f5; }
.chat-header strong, .chat-header span { display: block; }.chat-header span { margin-top: 3px; color: #9098a9; font-size: 11px; }
.task-strip { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 7px 12px; border-bottom: 1px solid #edf0f5; background: #fbfcff; }
.task-caption strong, .task-caption small { display: block; }.task-caption strong { color: #4053a6; font-size: 11px; }.task-caption small { margin-top: 2px; color: #9aa2b2; font-size: 9px; }.task-actions { display: flex; gap: 5px; }.task-actions .el-button { margin-left: 0; padding: 5px 8px; font-size: 10px; }
.task-result { margin: 8px 12px 0; padding: 8px 10px; border: 1px solid #dce5f2; border-radius: 8px; background: #f7faff; }.task-result-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #4053a6; font-size: 10px; }.task-result p { margin: 4px 0 2px; color: #596780; font-size: 11px; }.task-result > small { color: #8b96ab; font-size: 9px; }
.workflow-panel, .vision-panel { margin: 7px 12px 0; padding: 8px 10px; border: 1px solid #dce5f2; border-radius: 9px; background: #fbfcff; max-height: 230px; overflow: auto; }
.workflow-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 7px; }.workflow-heading strong, .workflow-heading small { display: block; }.workflow-heading strong { color: #4053a6; font-size: 11px; }.workflow-heading small { margin-top: 2px; color: #8993a7; font-size: 9px; }
.workflow-form { display: grid; grid-template-columns: 1fr 1fr 1.5fr auto; align-items: center; gap: 5px; }.workflow-form .el-input { min-width: 0; }.workflow-state { margin-top: 8px; padding-top: 7px; border-top: 1px solid #e6eaf2; color: #596780; font-size: 10px; }.workflow-state-head { display: flex; align-items: center; gap: 8px; }.workflow-state pre { max-height: 100px; margin: 4px 0; padding: 7px; overflow: auto; color: #48556e; border-radius: 6px; background: #f1f4fa; font: inherit; white-space: pre-wrap; word-break: break-word; }.workflow-state p { margin: 5px 0; }.workflow-confirm-actions { display: flex; justify-content: flex-end; gap: 6px; margin-top: 7px; }.workflow-result { color: #465d91; }
.vision-panel { max-height: 145px; }.vision-form { display: flex; align-items: center; gap: 8px; }.vision-form input { min-width: 0; flex: 1; color: #758098; font-size: 10px; }.vision-result { margin-top: 5px; color: #596780; font-size: 10px; }.vision-result p { margin: 4px 0; }
.status-dots { display: flex; align-items: center; gap: 6px; color: #778196; font-size: 12px; }
.status-dots span { width: 8px; height: 8px; margin-left: 8px; border-radius: 50%; background: #d15c64; }.status-dots span.online { background: #35b87f; box-shadow: 0 0 0 4px rgba(53,184,127,.12); }
.message-list { flex: 1; min-height: 0; overflow-y: auto; padding: 14px; background: linear-gradient(180deg, #fbfcff, #f7f9fd); }
.message-row { display: flex; gap: 10px; margin-bottom: 18px; }.message-row.user { flex-direction: row-reverse; }
.avatar { display: grid; place-items: center; width: 34px; height: 34px; flex-shrink: 0; color: white; border-radius: 11px; background: linear-gradient(135deg, #586bd9, #7359d2); }.user .avatar { background: linear-gradient(135deg, #1b9f91, #2576ad); }
.bubble-wrap { max-width: 74%; }.bubble { padding: 12px 15px; color: #34405a; border: 1px solid #e5e9f2; border-radius: 4px 14px 14px; background: white; line-height: 1.7; white-space: pre-wrap; }.user .bubble { color: white; border: none; border-radius: 14px 4px 14px 14px; background: linear-gradient(135deg, #3c56c7, #536bdc); }
.message-meta { margin-top: 5px; color: #9aa2b2; font-size: 10px; }.typing { display: flex; align-items: center; gap: 5px; }.typing span { width: 7px; height: 7px; border-radius: 50%; background: #6577dc; animation: pulse 1.2s infinite; }.typing span:nth-child(2) { animation-delay: .15s; }.typing span:nth-child(3) { animation-delay: .3s; }.typing em { margin-left: 6px; color: #7b8499; font-size: 11px; font-style: normal; }
.trace-panel { display: grid; gap: 5px; margin-top: 7px; padding: 8px; color: #69758c; border: 1px solid #e1e7f1; border-radius: 8px; background: #fbfcff; font-size: 9px; }.trace-summary, .trace-stages, .evidence-report { display: flex; flex-wrap: wrap; gap: 5px 10px; }.evidence-report { padding-top: 5px; border-top: 1px solid #e8ecf3; }.evidence-report .el-alert { width: 100%; }
.graph-evidence { margin-top: 7px; padding: 8px; color: #62718a; border: 1px solid #dce5f2; border-radius: 8px; background: #f8faff; font-size: 10px; }.graph-evidence summary { color: #4053a6; cursor: pointer; font-weight: 700; }.graph-evidence p { margin: 6px 0 0; }.graph-evidence ul, .vision-observations { margin: 5px 0; padding-left: 18px; }.vision-observations { color: #596780; font-size: 10px; }
.source-list { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
.source-card { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px 10px; color: #50607a; text-align: left; border: 1px solid #dce5f2; border-radius: 8px; background: #f7faff; cursor: pointer; }
.source-card:hover { border-color: #7b8de1; background: #f0f3ff; }.source-card > span { min-width: 0; flex: 1; }.source-card strong, .source-card small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.source-card strong { color: #4053a6; font-size: 11px; }.source-card small { margin-top: 2px; color: #8c96a7; font-size: 9px; }.source-arrow { color: #8290bd; }
.composer { padding: 10px 14px; border-top: 1px solid #edf0f5; background: white; }.composer-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 6px; }.composer-footer span { color: #9aa2b2; font-size: 10px; }
.source-dialog-head { display: flex; align-items: center; gap: 10px; padding: 12px; color: #4053a6; border-radius: 10px; background: #f2f5ff; }.source-dialog-head strong, .source-dialog-head span { display: block; }.source-dialog-head span { margin-top: 4px; color: #8993a7; font-size: 11px; }
.source-dialog-toolbar { display: flex; justify-content: space-between; align-items: center; margin: 14px 2px 7px; color: #68738b; font-size: 12px; }.source-fulltext { border: 1px solid #e3e8f2; border-radius: 9px; background: #fafbfe; }.source-fulltext pre { margin: 0; padding: 16px; color: #39455e; font: inherit; line-height: 1.8; white-space: pre-wrap; word-break: break-word; }
@keyframes pulse { 0%, 70%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-3px); } }
@media (max-width: 1100px) { .agent-page { height: auto; min-height: calc(100vh - 84px); overflow: auto; }.metric-grid { grid-template-columns: repeat(2, 1fr); }.workspace-grid { grid-template-columns: 1fr; }.chat-panel { height: 760px; }.hero-panel { flex-direction: column; }.workflow-form { grid-template-columns: 1fr 1fr; }.workflow-form .el-button { justify-self: start; } }
.customer-tools { flex: 0 0 auto; margin: 0 12px; border-bottom: 1px solid #edf0f5; }
.customer-tools > summary { padding: 9px 0; color: #4053a6; font-size: 12px; cursor: pointer; }
.tools-scroll { max-height: 240px; overflow-y: auto; padding-bottom: 8px; }
.tools-scroll .workflow-panel, .tools-scroll .vision-panel { max-height: none; overflow: visible; margin: 5px 0; }
.trace-details { margin-top: 7px; color: #4053a6; font-size: 11px; }
.trace-details > summary { padding: 5px 0; cursor: pointer; }
.explain-panel { overflow-y: auto; }
.pipeline { grid-template-columns: minmax(0, 1fr); }
.pipeline-step > div { min-width: 0; }
.pipeline-step strong { font-size: 11px; }
.hero-panel p { font-size: 12px; }
.chat-header, .task-strip, .composer { flex-shrink: 0; }
.message-list { min-height: 140px; }
</style>
