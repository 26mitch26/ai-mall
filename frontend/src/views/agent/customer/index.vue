<template>
  <div class="agent-page customer-page">
    <section class="hero-panel">
      <div>
        <div class="eyebrow">LOCAL RAG COPILOT</div>
        <h1>智能客服 Agent</h1>
        <p>本地 Ollama 驱动的可解释混合 RAG：语义召回与关键词召回并行，再经融合和重排生成可靠答案。</p>
      </div>
      <div class="hero-actions">
        <el-tag :type="connected ? 'success' : 'danger'" effect="dark" round>
          {{ connected ? '服务在线' : '服务离线' }}
        </el-tag>
        <el-button :icon="Refresh" circle @click="loadStatus" />
        <el-button type="primary" :icon="UploadFilled" @click="knowledgeVisible = true">导入知识</el-button>
      </div>
    </section>

    <section class="metric-grid">
      <div class="metric-card violet">
        <span class="metric-label">生成模型</span>
        <strong>{{ ragStatus?.chatModel || 'qwen3.5-noVL:latest' }}</strong>
        <small>Ollama · 本地推理</small>
      </div>
      <div class="metric-card cyan">
        <span class="metric-label">Embedding</span>
        <strong>{{ ragStatus?.embeddingModel || 'bge-m3' }}</strong>
        <small>1024 维语义向量</small>
      </div>
      <div class="metric-card blue">
        <span class="metric-label">向量检索</span>
        <strong>{{ ragStatus?.vectorStore || 'Milvus' }} · {{ ragStatus?.vectorIndex || 'ANN' }}</strong>
        <small>近似最近邻召回</small>
      </div>
      <div class="metric-card green">
        <span class="metric-label">最近响应</span>
        <strong>{{ lastLatency ? `${lastLatency} ms` : '等待提问' }}</strong>
        <small>语义缓存已启用</small>
      </div>
    </section>

    <section class="workspace-grid">
      <aside class="explain-panel">
        <div class="panel-title">检索链路</div>
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
          <span>{{ collaboration?.routeSummary || '接待分流 → 专家 Agent → 协同汇总' }}</span>
        </div>
        <div class="agent-team">
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
            <el-button size="small" :loading="taskLoading" @click="runTask('创建售后工单')">创建售后</el-button>
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

        <div ref="messageListRef" class="message-list">
          <div v-for="(message, index) in messages" :key="index" :class="['message-row', message.role]">
            <div class="avatar">
              <el-icon><User v-if="message.role === 'user'" /><Service v-else /></el-icon>
            </div>
            <div class="bubble-wrap">
              <div class="bubble">{{ message.content }}</div>
              <div v-if="message.latency" class="message-meta">
                {{ message.intent || 'general' }} · {{ message.latency }} ms · RAG grounded
              </div>
              <div v-if="message.sources?.length" class="source-list">
                <button v-for="source in message.sources" :key="source.id" class="source-card" @click="openSource(source)">
                  <el-icon><DocumentIcon /></el-icon>
                  <span><strong>{{ source.source }}</strong><small>{{ source.type }} · {{ source.retrievalSource }} · {{ source.score.toFixed(2) }}</small></span>
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
            :disabled="loading || !connected"
            @keydown.enter.exact.prevent="sendMessage"
          />
          <div class="composer-footer">
            <span>Enter 发送 · 回答仅基于工具和知识库</span>
            <el-button type="primary" :icon="Promotion" :loading="loading" @click="sendMessage">发送</el-button>
          </div>
        </div>
      </main>
    </section>

    <el-dialog v-model="sourceVisible" title="回答依据 · 原文全文" width="720px">
      <div v-if="selectedSource" class="source-dialog">
        <div class="source-dialog-head">
          <el-icon><DocumentIcon /></el-icon>
          <div><strong>{{ selectedSource.source }}</strong><span>{{ selectedSource.type }} · {{ selectedSource.retrievalSource }} · 命中分数 {{ selectedSource.score.toFixed(2) }}</span></div>
        </div>
        <div class="source-dialog-toolbar">
          <span>知识库原文</span>
          <el-button link type="primary" :loading="sourceLoading" @click="loadFullSource(selectedSource)">重新加载全文</el-button>
        </div>
        <el-scrollbar max-height="430px" class="source-fulltext">
          <pre>{{ sourceFullContent || selectedSource.content }}</pre>
        </el-scrollbar>
        <el-alert v-if="sourceNotFound" title="未找到全文索引，当前显示本次命中的原文片段。" type="warning" :closable="false" show-icon />
      </div>
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
import { ArrowRight, Connection, Document as DocumentIcon, Promotion, Refresh, Service, UploadFilled, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getRagStatusAPI,
  getKnowledgeSourceAPI,
  executeTaskAPI,
  ingestKnowledgeAPI,
  sendChatMessageAPI,
  type AgentCollaboration,
  type CollaborationPlan,
  type RagStatus,
  type SourceReference,
  type TaskResponse,
} from '@/apis/agent'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  latency?: number
  intent?: string
  sources?: SourceReference[]
}

const messages = ref<ChatMessage[]>([
  { role: 'assistant', content: '你好，我是 AI-Mall 智能客服。你可以询问退换货、配送、支付或商品使用问题。' },
])
const inputMessage = ref('')
const loading = ref(false)
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
const defaultAgents: AgentCollaboration[] = [
  { id: 'router', name: '接待分流 Agent', role: '接待', description: '识别意图并分派专家', status: 'completed', active: true },
  { id: 'order', name: '订单管理 Agent', role: '专家', description: '查询订单、退换货与物流', status: 'standby', active: false },
  { id: 'technical', name: '技术支持 Agent', role: '专家', description: '定位故障并给出处理方案', status: 'standby', active: false },
  { id: 'complaint', name: '投诉处理 Agent', role: '专家', description: '安抚情绪、记录反馈并闭环', status: 'standby', active: false },
  { id: 'coordinator', name: '协同汇总 Agent', role: '编排', description: '融合知识库与专家结果后回复', status: 'completed', active: true },
]
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
const visibleAgents = computed(() => collaboration.value?.agents?.length ? collaboration.value.agents : defaultAgents)
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
  if (!content || loading.value || !connected.value) return
  messages.value.push({ role: 'user', content })
  inputMessage.value = ''
  loading.value = true
  scrollToBottom()
  try {
    const response = await sendChatMessageAPI({ message: content, sessionId: sessionId.value })
    sessionId.value = response.data.sessionId
    lastLatency.value = response.data.responseTime
    messages.value.push({
      role: 'assistant',
      content: response.data.answer,
      latency: response.data.responseTime,
      intent: response.data.intent,
      sources: response.data.sources || [],
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
  sourceFullContent.value = source.content
  sourceNotFound.value = false
  sourceVisible.value = true
  loadFullSource(source)
}

const loadFullSource = async (source: SourceReference) => {
  sourceLoading.value = true
  sourceNotFound.value = false
  try {
    const response = await getKnowledgeSourceAPI(source.source)
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
    const response = await executeTaskAPI({ message, sessionId: sessionId.value })
    taskResult.value = response.data
    sessionId.value = response.data.sessionId
  } catch (error: any) {
    ElMessage.error(`任务执行失败：${error?.message || '请检查客服 Agent'}`)
  } finally {
    taskLoading.value = false
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

onMounted(loadStatus)
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
.status-dots { display: flex; align-items: center; gap: 6px; color: #778196; font-size: 12px; }
.status-dots span { width: 8px; height: 8px; margin-left: 8px; border-radius: 50%; background: #d15c64; }.status-dots span.online { background: #35b87f; box-shadow: 0 0 0 4px rgba(53,184,127,.12); }
.message-list { flex: 1; min-height: 0; overflow-y: auto; padding: 14px; background: linear-gradient(180deg, #fbfcff, #f7f9fd); }
.message-row { display: flex; gap: 10px; margin-bottom: 18px; }.message-row.user { flex-direction: row-reverse; }
.avatar { display: grid; place-items: center; width: 34px; height: 34px; flex-shrink: 0; color: white; border-radius: 11px; background: linear-gradient(135deg, #586bd9, #7359d2); }.user .avatar { background: linear-gradient(135deg, #1b9f91, #2576ad); }
.bubble-wrap { max-width: 74%; }.bubble { padding: 12px 15px; color: #34405a; border: 1px solid #e5e9f2; border-radius: 4px 14px 14px; background: white; line-height: 1.7; white-space: pre-wrap; }.user .bubble { color: white; border: none; border-radius: 14px 4px 14px 14px; background: linear-gradient(135deg, #3c56c7, #536bdc); }
.message-meta { margin-top: 5px; color: #9aa2b2; font-size: 10px; }.typing { display: flex; align-items: center; gap: 5px; }.typing span { width: 7px; height: 7px; border-radius: 50%; background: #6577dc; animation: pulse 1.2s infinite; }.typing span:nth-child(2) { animation-delay: .15s; }.typing span:nth-child(3) { animation-delay: .3s; }.typing em { margin-left: 6px; color: #7b8499; font-size: 11px; font-style: normal; }
.source-list { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
.source-card { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px 10px; color: #50607a; text-align: left; border: 1px solid #dce5f2; border-radius: 8px; background: #f7faff; cursor: pointer; }
.source-card:hover { border-color: #7b8de1; background: #f0f3ff; }.source-card > span { min-width: 0; flex: 1; }.source-card strong, .source-card small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.source-card strong { color: #4053a6; font-size: 11px; }.source-card small { margin-top: 2px; color: #8c96a7; font-size: 9px; }.source-arrow { color: #8290bd; }
.composer { padding: 10px 14px; border-top: 1px solid #edf0f5; background: white; }.composer-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 6px; }.composer-footer span { color: #9aa2b2; font-size: 10px; }
.source-dialog-head { display: flex; align-items: center; gap: 10px; padding: 12px; color: #4053a6; border-radius: 10px; background: #f2f5ff; }.source-dialog-head strong, .source-dialog-head span { display: block; }.source-dialog-head span { margin-top: 4px; color: #8993a7; font-size: 11px; }
.source-dialog-toolbar { display: flex; justify-content: space-between; align-items: center; margin: 14px 2px 7px; color: #68738b; font-size: 12px; }.source-fulltext { border: 1px solid #e3e8f2; border-radius: 9px; background: #fafbfe; }.source-fulltext pre { margin: 0; padding: 16px; color: #39455e; font: inherit; line-height: 1.8; white-space: pre-wrap; word-break: break-word; }
@keyframes pulse { 0%, 70%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-3px); } }
@media (max-width: 1100px) { .agent-page { height: auto; min-height: calc(100vh - 84px); overflow: auto; }.metric-grid { grid-template-columns: repeat(2, 1fr); }.workspace-grid { grid-template-columns: 1fr; }.chat-panel { height: 560px; }.hero-panel { flex-direction: column; } }
</style>
