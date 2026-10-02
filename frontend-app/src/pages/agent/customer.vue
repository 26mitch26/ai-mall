<template>
  <view class="agent-page">
    <view class="agent-shell">
      <view class="agent-hero">
        <view>
          <text class="eyebrow">AI-MALL CUSTOMER CARE</text>
          <text class="hero-title">智能客服</text>
          <text class="hero-subtitle">本地 Ollama + RAG，为会员提供退换货、配送、商品和订单咨询。</text>
        </view>
        <view class="hero-status"><text :class="['status-dot', { offline: !serviceOnline }]"></text><text>{{ serviceOnline ? '本地服务在线' : '服务未连接' }}</text></view>
      </view>
      <view v-if="!hasToken" class="login-hint" @click="goLogin">请先登录商城会员账号 demo / Demo@123，再使用订单与客服服务 →</view>

      <view class="agent-layout">
        <view class="agent-intro">
          <view class="intro-card">
            <text class="card-title">你可以这样问</text>
            <view v-for="question in quickQuestions" :key="question" class="quick-question" @click="ask(question)">
              <text>{{ question }}</text><text class="arrow">›</text>
            </view>
          </view>
          <view class="intro-card model-card">
            <text class="card-title">回答依据</text>
            <text class="model-line">Ollama · {{ agentStatus?.chatModel || 'qwen3.5-noVL' }}</text>
            <text class="model-line">{{ agentStatus?.vectorStore || 'Milvus' }} ANN + BM25 + RRF</text>
            <text class="model-line">Embedding · {{ agentStatus?.embeddingModel || 'bge-m3' }}</text>
            <text class="model-line">知识库 · {{ agentStatus?.knowledgeBase?.documents ?? '—' }} 篇文档 / {{ agentStatus?.knowledgeBase?.chunks ?? '—' }} 个分块</text>
            <text class="model-line pipeline-line">检索链路：{{ pipelineText }}</text>
            <text class="model-tip">政策问答返回知识库来源与相关度，可展开查看原文；商品价格、库存实时查询本地商城接口。</text>
          </view>
        </view>

        <view class="chat-card">
          <scroll-view class="message-list" scroll-y :scroll-into-view="scrollTarget">
            <view v-for="(message, index) in messages" :id="`message-${index}`" :key="index" :class="['message-row', message.role]">
              <view class="message-avatar">{{ message.role === 'assistant' ? 'AI' : '我' }}</view>
              <view class="message-content">
                <text class="message-bubble">{{ message.content }}</text>
                <text v-if="message.meta" class="message-meta">{{ message.meta }}</text>
                <text v-if="message.route" class="message-route">协作链路：{{ message.route }}</text>
                <text v-if="message.decision" :class="['message-decision', message.decision.includes('依据充分') || message.decision.includes('实时') ? 'ok' : 'warn']">{{ message.decision }}</text>
                <view v-if="message.sources?.length" class="source-list">
                  <view v-for="source in message.sources" :key="source.id" class="source-item">
                    <view class="source-head">
                      <text class="source-name">{{ source.source }}</text>
                      <text class="source-action" @click="toggleSourceFull(source)">{{ expandedSources[source.source] ? '收起原文' : '查看原文' }}</text>
                    </view>
                    <text class="source-meta">{{ source.retrievalSource }} · 相关度 {{ source.score?.toFixed(2) }}</text>
                    <text class="source-excerpt">{{ source.content }}</text>
                    <text v-if="expandedSources[source.source]" class="source-full">{{ expandedSources[source.source] }}</text>
                  </view>
                </view>
              </view>
            </view>
            <view v-if="loading" class="message-row assistant">
              <view class="message-avatar">AI</view><text class="message-bubble loading-text">{{ loadingText }}</text>
            </view>
          </scroll-view>
          <view class="composer">
            <textarea v-model="inputMessage" class="composer-input" auto-height maxlength="500" placeholder="例如：有哪些手机卖？" @confirm="sendMessage" />
            <button class="send-button" :disabled="loading || !inputMessage.trim()" @click="sendMessage">发送</button>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { customerChatAPI, agentStatusAPI, knowledgeSourceAPI, type AgentSource, type AgentStatus } from '@/apis/agent'
import { getMemberInfoAPI } from '@/apis/member'
import { useMemberStore } from '@/stores/member'

interface Message {
  role: 'user' | 'assistant'
  content: string
  meta?: string
  route?: string
  decision?: string
  sources?: AgentSource[]
}

const memberStore = useMemberStore()
const sessionId = ref(`member_${Date.now()}`)
const inputMessage = ref('')
const loading = ref(false)
const loadingText = ref('正在检索知识库…')
const hasToken = ref(false)
const serviceOnline = ref(false)
const agentStatus = ref<AgentStatus | null>(null)
const scrollTarget = ref('')
/** 来源卡片"查看原文"展开内容：source 文件名 -> 全文 */
const expandedSources = ref<Record<string, string>>({})
const messages = ref<Message[]>([
  { role: 'assistant', content: '你好，我是 AI-Mall 智能客服。可以帮你查商品、问价格库存，也可以咨询配送、退换货；告诉我需求还能直接帮你下单。' },
])
const quickQuestions = ['退款一般多久到账？', '换货的运费由谁承担？', '重复扣款了怎么办？', '有哪些手机卖？', '帮我买华为Mate60 Pro', '我想买个耳机', '我现在买了哪些东西', '我想取消订单', '帮我查订单 202609300001', '你们有线下门店吗？']
let loadingTimer: ReturnType<typeof setTimeout> | undefined

/** 检索链路展示：优先取服务端返回的 pipeline，失败时用默认链路兜底 */
const pipelineText = computed(() =>
  (agentStatus.value?.pipeline || ['Query 改写', 'ANN 语义召回', 'BM25 关键词召回', 'RRF 融合', '特征重排', 'Ollama 生成']).join(' → '),
)

const ask = (question: string) => {
  inputMessage.value = question
  sendMessage()
}

const goLogin = () => {
  uni.navigateTo({ url: '/pages/public/login' })
}

/** 加载本地 RAG 服务状态，作为顶部"在线/离线"标识与知识库规模的展示依据。
 *  首屏偶发失败（服务重启窗口/网络抖动）时做一次延迟重试，避免页头一直显示"服务未连接"。 */
const loadStatus = async (retried = false) => {
  try {
    const response = await agentStatusAPI()
    agentStatus.value = response.data
    serviceOnline.value = Boolean(response.data?.online && response.data?.ollamaOnline)
  } catch {
    if (!retried) {
      setTimeout(() => loadStatus(true), 1200)
      return
    }
    agentStatus.value = null
    serviceOnline.value = false
  }
}

/** 展开/收起来源卡片原文（走知识库来源全文接口，展示 Citation 可溯源） */
const toggleSourceFull = async (source: AgentSource) => {
  if (expandedSources.value[source.source]) {
    delete expandedSources.value[source.source]
    return
  }
  try {
    const response = await knowledgeSourceAPI(source.source)
    expandedSources.value[source.source] = response.data?.found ? response.data.content : '（未找到原文）'
  } catch {
    expandedSources.value[source.source] = '原文读取失败，请稍后重试'
  }
}

/**
 * 解析当前登录会员 ID：优先取本地会员信息，缺失时用令牌向商城拉取一次。
 * 订单查询、创建售后等敏感工具依赖该 ID 做身份绑定，缺少时会被后端安全拒绝。
 */
const resolveMemberId = async (): Promise<string | undefined> => {
  if (memberStore.memberInfo?.id) return String(memberStore.memberInfo.id)
  try {
    const response = await getMemberInfoAPI()
    memberStore.setMemberInfo(response.data)
    return response.data?.id ? String(response.data.id) : undefined
  } catch {
    return undefined
  }
}

const sendMessage = async () => {
  const content = inputMessage.value.trim()
  if (!content || loading.value) return
  hasToken.value = Boolean(uni.getStorageSync('token'))
  if (!hasToken.value) {
    uni.showToast({ icon: 'none', title: '请先登录商城会员账号' })
    goLogin()
    return
  }
  messages.value.push({ role: 'user', content })
  inputMessage.value = ''
  loading.value = true
  loadingText.value = '正在检索知识库…'
  // 本地模型推理较慢时给出阶段提示，避免用户以为卡死
  loadingTimer = setTimeout(() => { loadingText.value = '正在生成回答（本地模型推理中，请稍候）…' }, 3500)
  try {
    const userId = await resolveMemberId()
    const response = await customerChatAPI({ sessionId: sessionId.value, message: content, userId })
    sessionId.value = response.data.sessionId
    messages.value.push({
      role: 'assistant',
      content: response.data.answer,
      meta: `${response.data.intent} · ${response.data.responseTime} ms`,
      route: response.data.collaboration?.routeSummary,
      decision: response.data.retrievalDecision,
      sources: response.data.sources || [],
    })
  } catch {
    messages.value.push({ role: 'assistant', content: '暂时无法连接客服服务，请稍后重试。' })
  } finally {
    if (loadingTimer) clearTimeout(loadingTimer)
    loading.value = false
    setTimeout(() => { scrollTarget.value = `message-${messages.value.length - 1}` }, 50)
  }
}

onLoad(() => {
  hasToken.value = Boolean(uni.getStorageSync('token'))
  scrollTarget.value = 'message-0'
  loadStatus()
})
onShow(() => {
  hasToken.value = Boolean(uni.getStorageSync('token'))
  // 回到页面时状态缺失则补拉（例如首屏加载失败后重试仍失败的情况）
  if (!serviceOnline.value) {
    loadStatus()
  }
})
</script>

<style lang="scss">
page { background: #f3f6fb; }
/* 桌面优先（Web 演示为主）：基础字号 13~15px、卡片间距放大；小屏在底部媒体查询中回退为移动端尺寸 */
.agent-page { min-height: 100vh; padding: 32px 28px 48px; box-sizing: border-box; background: #f3f6fb; }
.agent-shell { width: min(1280px, 100%); margin: 0 auto; }
.login-hint { margin-top: 14px; padding: 13px 18px; border: 1px solid #f0d7a1; border-radius: 10px; color: #8a6423; background: #fff8e8; font-size: 13px; cursor: pointer; }
.login-hint:hover { background: #fff3d8; }
.agent-hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 34px 40px; border-radius: 22px; color: #fff; background: linear-gradient(120deg, #172343, #314b9a 60%, #137777); box-shadow: 0 16px 40px rgba(35, 62, 125, .18); }
.eyebrow { display: block; color: #9fe8ee; font-size: 13px; letter-spacing: 2px; }.hero-title { display: block; margin-top: 8px; font-size: 34px; font-weight: 700; }.hero-subtitle { display: block; max-width: 720px; margin-top: 8px; color: #dce8ff; font-size: 15px; line-height: 1.7; }.hero-status { display: flex; align-items: center; gap: 8px; padding: 10px 15px; border: 1px solid rgba(255,255,255,.24); border-radius: 99px; color: #e3f8ed; font-size: 13px; white-space: nowrap; }.status-dot { width: 9px; height: 9px; border-radius: 50%; background: #4fe09b; box-shadow: 0 0 0 4px rgba(79,224,155,.16); }.status-dot.offline { background: #d15c64; box-shadow: 0 0 0 4px rgba(209,92,100,.16); }
.agent-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 20px; margin-top: 20px; }.agent-intro { display: flex; flex-direction: column; gap: 16px; }.intro-card, .chat-card { border: 1px solid #e1e7f1; border-radius: 17px; background: #fff; box-shadow: 0 9px 28px rgba(30, 55, 100, .06); }.intro-card { padding: 20px; }.card-title { display: block; margin-bottom: 14px; color: #263452; font-size: 16px; font-weight: 700; }.quick-question { display: flex; align-items: center; justify-content: space-between; padding: 13px 10px; border-bottom: 1px solid #eef1f6; color: #54617a; font-size: 14px; cursor: pointer; transition: color .15s, background .15s; }.quick-question:last-child { border-bottom: 0; }.quick-question:hover { color: #3d60d3; background: #f6f9ff; border-radius: 8px; }.arrow { color: #8e9ab0; font-size: 20px; }.model-card { background: #f8faff; }.model-line { display: block; margin: 9px 0; color: #556ba9; font-size: 13px; }.pipeline-line { margin-top: 12px; color: #6d7fa8; font-size: 12px; line-height: 1.8; }.model-tip { display: block; margin-top: 14px; color: #929db0; font-size: 12px; line-height: 1.7; }
.chat-card { display: flex; min-height: 660px; flex-direction: column; overflow: hidden; }.message-list { flex: 1; min-height: 0; padding: 26px; box-sizing: border-box; background: linear-gradient(180deg, #fbfcff, #f6f8fc); }.message-row { display: flex; gap: 12px; margin-bottom: 22px; }.message-row.user { flex-direction: row-reverse; }.message-avatar { display: grid; place-items: center; width: 36px; height: 36px; flex: 0 0 auto; border-radius: 11px; color: #fff; background: linear-gradient(135deg, #536bd8, #7359d2); font-size: 11px; }.user .message-avatar { background: linear-gradient(135deg, #1a9b8c, #2476af); }.message-content { display: flex; max-width: 74%; flex-direction: column; align-items: flex-start; }.user .message-content { align-items: flex-end; }.message-bubble { display: block; padding: 13px 16px; border: 1px solid #e2e7f0; border-radius: 4px 14px 14px; color: #39455e; background: #fff; font-size: 15px; line-height: 1.75; white-space: pre-wrap; }.user .message-bubble { border: 0; border-radius: 14px 4px 14px 14px; color: #fff; background: linear-gradient(135deg, #3d56c6, #536bdc); }.message-meta { margin-top: 6px; color: #9aa3b2; font-size: 12px; }.message-route { margin-top: 4px; color: #5a72c2; font-size: 12px; }.message-decision { display: block; margin-top: 4px; font-size: 12px; font-weight: 600; }.message-decision.ok { color: #2f7d5d; }.message-decision.warn { color: #a05a2c; }.loading-text { color: #75829a; }.source-list { width: 100%; margin-top: 8px; }.source-item { margin-top: 6px; padding: 12px 14px; border: 1px solid #dfe6f1; border-radius: 10px; background: #f8faff; }.source-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }.source-name, .source-meta, .source-excerpt, .source-full { display: block; }.source-name { color: #4053a6; font-size: 13px; font-weight: 600; }.source-action { flex: 0 0 auto; color: #3d60d3; font-size: 12px; text-decoration: underline; cursor: pointer; white-space: nowrap; }.source-meta { margin-top: 3px; color: #94a0b8; font-size: 12px; }.source-excerpt { margin-top: 4px; color: #7a8599; font-size: 13px; line-height: 1.6; }.source-full { margin-top: 8px; padding: 10px 12px; border-top: 1px dashed #dfe6f1; color: #5b6a8a; background: #fff; font-size: 13px; line-height: 1.8; white-space: pre-wrap; }
.composer { display: flex; align-items: flex-end; gap: 12px; padding: 16px; border-top: 1px solid #edf0f5; background: #fff; }.composer-input { flex: 1; min-height: 46px; max-height: 120px; padding: 12px 14px; box-sizing: border-box; border: 1px solid #dfe5ef; border-radius: 10px; color: #34405a; font-size: 15px; }.composer-input:focus { border-color: #7c90e8; }.send-button { width: 88px; height: 46px; margin: 0; padding: 0; border-radius: 10px; color: #fff; background: #5268d5; font-size: 14px; cursor: pointer; }.send-button:hover { background: #4460cf; }.send-button[disabled] { opacity: .5; cursor: not-allowed; }
@media (max-width: 760px) { .agent-page { padding: 0 0 24px; }.agent-hero { flex-direction: column; align-items: flex-start; padding: 23px 20px; border-radius: 0 0 18px 18px; }.hero-title { font-size: 26px; }.hero-subtitle { font-size: 13px; }.agent-layout { display: flex; flex-direction: column; margin-top: 12px; }.agent-intro { order: 2; padding: 0 12px; }.chat-card { order: 1; min-height: 620px; border-right: 0; border-left: 0; border-radius: 0; }.message-list { padding: 16px 12px; }.message-content { max-width: 84%; }.message-bubble { font-size: 14px; }.message-meta, .message-route { font-size: 11px; }.source-name, .source-excerpt, .source-full { font-size: 12px; }.source-meta, .source-action { font-size: 11px; }.intro-card { padding: 15px; }.quick-question { font-size: 13px; }.model-line { font-size: 12px; }.pipeline-line, .model-tip { font-size: 11px; } }
</style>
