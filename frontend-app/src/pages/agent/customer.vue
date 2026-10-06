<template>
  <view class="agent-page pc-storefront-page">
    <view class="agent-shell">
      <pc-storefront-nav active="customer" />
      <header class="agent-hero">
        <view><text class="eyebrow">AI-MALL · 会员服务</text><text class="hero-title">智能客服</text><text class="hero-subtitle">咨询商品与商城政策，或查看订单并准备售后申请。</text></view>
        <text class="service-status"><i :class="['status-dot', { offline: !serviceOnline }]" />{{ serviceOnline ? '客服在线' : '服务未连接' }}</text>
      </header>

      <view v-if="!hasToken" class="login-hint"><text>登录后可查询个人订单、准备售后申请。</text><button class="login-button" @click="goLogin">会员登录</button></view>

      <view class="workspace">
        <view class="chat-card">
          <view class="chat-heading"><view><text class="heading-title">和智能客服对话</text><text class="heading-subtitle">访客可咨询政策和公开商品信息</text></view><button class="history-button" @click="clearConversation">新对话</button></view>
          <scroll-view class="message-list" scroll-y :scroll-into-view="scrollTarget">
            <view v-for="(message, index) in messages" :id="`message-${index}`" :key="`${message.id || index}`" :class="['message-row', message.role]">
              <view class="message-avatar">{{ message.role === 'assistant' ? 'AI' : '我' }}</view>
              <view class="message-content">
                <text class="message-bubble">{{ message.content }}</text>
                <text v-if="message.meta" class="message-meta">{{ message.meta }}</text>
                <text v-if="message.evidenceReport && message.evidenceReport.unsupportedNumericClaims > 0" class="debug-line warn">部分金额或时效表述尚未通过原文核对，请查看政策与适用条件。</text>
                <view v-if="message.sources?.length" class="source-list">
                  <view v-for="source in message.sources" :key="`${source.id}:${source.version || ''}`" class="source-item">
                    <view class="source-head"><text class="source-name">{{ source.source }}</text><text class="source-action" @click="toggleSourceFull(source)">{{ expandedSources[sourceKey(source)] ? '收起原文' : '查看原文' }}</text></view>
                    <text class="source-meta">{{ source.version ? `政策版本 ${source.version}` : '参考资料' }}<template v-if="source.effectiveAt"> · 生效于 {{ formatDate(source.effectiveAt) }}</template><template v-if="source.score != null"> · 相关度 {{ formatScore(source.score) }}</template></text>
                    <text class="source-meta">{{ source.contentKind === 'selected-excerpt' ? '本次引用片段' : '来源预览' }}</text>
                    <text class="source-excerpt">{{ source.content }}</text>
                    <text v-if="expandedSources[sourceKey(source)]" class="source-full">{{ expandedSources[sourceKey(source)] }}</text>
                  </view>
                </view>
                <details v-if="message.trace || message.evidenceReport" class="technical-details"><summary>回答详情</summary>
                  <text v-if="message.route" class="debug-line">实际服务路径：{{ message.route }}</text>
                  <text v-if="message.retrievalDecision" class="debug-line">{{ message.retrievalDecision }}</text>
                  <text v-if="message.evidenceReport?.conflicts?.length" class="debug-line warn">待核对：{{ message.evidenceReport.conflicts.join('；') }}</text>
                  <text v-if="message.trace?.traceId" class="debug-line">记录编号：{{ message.trace.traceId }}</text>
                  <text v-for="(stage, stageIndex) in message.trace?.stages || []" :key="stageIndex" class="debug-line">{{ stage.name }} · {{ stage.durationMs }} ms<template v-if="stage.outcome"> · {{ stage.outcome }}</template></text>
                  <text v-if="message.trace" class="debug-line">模型调用 {{ message.trace.modelCalls ?? '—' }} 次 · 输入 {{ message.trace.inputTokens ?? '—' }} tokens · 输出 {{ message.trace.outputTokens ?? '—' }} tokens</text>
                </details>
              </view>
            </view>
            <view v-if="loading" class="message-row assistant"><view class="message-avatar">AI</view><text class="message-bubble loading-text">{{ loadingText }}</text></view>
          </scroll-view>
          <view class="quick-row"><button v-for="question in quickQuestions" :key="question" class="quick-chip" @click="ask(question)">{{ question }}</button></view>
          <view class="composer"><textarea v-model="inputMessage" class="composer-input" auto-height :maxlength="500" placeholder="输入问题，例如：退款一般多久到账？" @confirm="sendMessage" /><button class="send-button" :disabled="loading || !inputMessage.trim()" @click="sendMessage">{{ loading ? '处理中' : '发送' }}</button></view>
        </view>

        <aside class="side-panel">
          <details class="side-section" open><summary>帮助中心</summary><text class="section-copy">查看政策问答引用的版本和原文；也可直接在聊天中提问。</text>
            <view v-for="doc in helpDocuments" :key="doc.source" class="help-row"><text class="help-title">{{ doc.title || doc.source }}</text><text class="help-type">{{ doc.type }}</text></view>
            <text v-if="!helpDocuments.length" class="empty-note">政策来源会随客服回答显示。</text>
          </details>
          <details class="side-section"><summary>我的订单</summary>
            <template v-if="hasMemberIdentity"><button class="panel-action" :disabled="ordersLoading" @click="loadOrders">{{ ordersLoading ? '正在加载…' : '查看近期订单' }}</button>
              <view v-for="order in orders" :key="order.id" class="order-row"><text class="order-number">{{ order.orderSn }}</text><text class="order-status">{{ orderStatus(order.status) }} · {{ order.createTime || '' }}</text></view>
              <text v-if="ordersLoaded && !orders.length" class="empty-note">暂时没有可展示的订单。</text>
            </template><view v-else class="side-login"><text>登录后查看你的订单。</text><text class="link" @click="goLogin">登录会员账号 →</text></view>
          </details>
          <details class="side-section after-sale-section"><summary>准备售后申请</summary>
            <template v-if="hasMemberIdentity"><text class="section-copy">先生成申请草稿，核对订单和说明后再确认；这一步不会直接退款。</text>
              <input v-model="afterSale.orderSn" class="field" placeholder="订单号" />
              <input v-model="afterSale.reason" class="field" placeholder="申请原因" />
              <textarea v-model="afterSale.description" class="field description" :maxlength="500" placeholder="补充说明" />
              <button class="panel-action" :disabled="workflowBusy || !canPrepare || prepareOutcomeUnknown" @click="prepareAfterSale">{{ workflowBusy ? '正在处理…' : prepareOutcomeUnknown ? '结果待核对' : '生成售后草稿' }}</button>
              <text v-if="prepareOutcomeUnknown" class="warn-note">请求结果未确认，请核对申请状态或联系人工客服；此时不能重复创建。</text>
              <button class="image-action" :disabled="visionBusy" @click="chooseEvidenceImage">{{ visionBusy ? '图片识别中…' : '上传图片辅助填写原因' }}</button>
              <text v-if="visionNote" class="section-copy">{{ visionNote }}</text>
              <view v-if="task" class="task-card"><text class="task-status">{{ taskStatusText }}</text><text v-if="task.orderSummary" class="task-detail">{{ formatOrderSummary(task.orderSummary) }}</text><text v-if="task.draft" class="task-detail">{{ task.draft }}</text><text v-if="task.policyAnswer" class="task-detail">政策依据：{{ task.policyAnswer }}</text>
                <view v-if="task.status === 'WAITING_CONFIRMATION'" class="task-actions"><button class="reject-button" :disabled="workflowBusy" @click="confirmTask(false)">拒绝草稿</button><button class="confirm-button" :disabled="workflowBusy || task.policyEvidenceWeak" @click="confirmTask(true)">确认并提交</button></view>
                <text v-if="task.policyEvidenceWeak" class="warn-note">政策证据不足，请先联系人工客服核对；当前不能直接提交。</text>
                <button v-if="task.taskId" class="refresh-button" :disabled="workflowBusy" @click="refreshTask">刷新申请状态</button>
              </view>
            </template><view v-else class="side-login"><text>登录后可准备个人售后申请。</text><text class="link" @click="goLogin">登录会员账号 →</text></view>
          </details>
          <details class="side-section"><summary>服务信息</summary><text class="section-copy">商品价格和库存以商城当前数据为准。政策原文按回答中的版本读取。</text></details>
        </aside>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { agentStatusAPI, confirmAfterSaleAPI, customerChatAPI, getAfterSaleAPI, inspectAfterSaleImageAPI, knowledgeDocumentsAPI, knowledgeSourceAPI, prepareAfterSaleAPI, type AfterSaleWorkflowState, type AgentSource, type AgentStatus, type CustomerChatResponse, type KnowledgeDocument, type VisionInspection } from '@/apis/agent'
import { getMemberInfoAPI } from '@/apis/member'
import { getOrderListAPI } from '@/apis/order'
import { useMemberStore } from '@/stores/member'
import PcStorefrontNav from '@/components/pc-storefront-nav.vue'

interface Message {
  id?: string
  role: 'user' | 'assistant'
  content: string
  meta?: string
  route?: string
  retrievalDecision?: string
  sources?: AgentSource[]
  trace?: CustomerChatResponse['trace']
  evidenceReport?: CustomerChatResponse['evidenceReport']
}
interface OrderItem { id: number; orderSn: string; status?: string | number; statusText?: string; createTime?: string }
interface SavedConversation { sessionId: string; messages: Message[]; taskId?: string }

const memberStore = useMemberStore()
const sessionId = ref(`guest_${Date.now()}`)
const accountScope = ref('guest')
const inputMessage = ref('')
const loading = ref(false)
const loadingText = ref('正在为你查找答案…')
const hasToken = ref(false)
const serviceOnline = ref(false)
const agentStatus = ref<AgentStatus | null>(null)
const scrollTarget = ref('')
const expandedSources = ref<Record<string, string>>({})
const messages = ref<Message[]>([{ role: 'assistant', content: '你好！我可以帮你了解商城政策、查询公开商品信息。登录后还可查询订单并准备售后申请。' }])
const quickQuestions = ['退款多久到账？', '有哪些手机在售？', '怎么申请换货？']
const helpDocuments = ref<KnowledgeDocument[]>([])
const orders = ref<OrderItem[]>([])
const ordersLoading = ref(false)
const ordersLoaded = ref(false)
const afterSale = ref({ orderSn: '', reason: '', description: '' })
const task = ref<AfterSaleWorkflowState | null>(null)
const taskId = ref('')
const workflowBusy = ref(false)
const visionBusy = ref(false)
const visionNote = ref('')
const prepareOutcomeUnknown = ref(false)
let loadingTimer: ReturnType<typeof setTimeout> | undefined
let switchingAccount = false

const canPrepare = computed(() => Boolean(hasMemberIdentity.value && afterSale.value.orderSn.trim() && afterSale.value.reason.trim()))
const hasMemberIdentity = computed(() => /^member-\d+$/.test(accountScope.value))
const taskStatusText = computed(() => ({ WAITING_CONFIRMATION: '待你确认的草稿', COMPLETED: '申请已完成', REJECTED: '草稿已拒绝', UNKNOWN: '结果待核对' }[task.value?.status || ''] || `申请状态：${task.value?.status || ''}`))
const goLogin = () => uni.navigateTo({ url: '/pages/public/login' })
const sourceKey = (source: AgentSource) => `${source.source}@${source.version || 'current'}`
const formatScore = (score: number | null | undefined) => typeof score === 'number' && Number.isFinite(score) ? score.toFixed(2) : '—'
const formatDate = (value: string) => value.slice(0, 10)
const orderStatus = (status?: string | number) => {
  const labels: Record<number, string> = { 0: '待付款', 1: '待发货', 2: '已发货', 3: '已完成', 4: '已关闭' }
  return labels[Number(status)] || '订单'
}
const conversationKey = (scope: string) => `member-assistant:${scope}`
const formatOrderSummary = (summary: string) => {
  try {
    const order = JSON.parse(summary) as { orderSn?: string; status?: number; payAmount?: number; orderItemList?: Array<{ productName?: string; productQuantity?: number }> }
    const lines = [`订单：${order.orderSn || '已核验'} · ${orderStatus(order.status)}`]
    if (typeof order.payAmount === 'number' && Number.isFinite(order.payAmount)) lines.push(`实付：¥${order.payAmount.toFixed(2)}`)
    for (const item of order.orderItemList?.slice(0, 5) || []) if (item.productName) lines.push(`${item.productName} × ${item.productQuantity ?? 1}`)
    return lines.join('\n')
  } catch { return '订单快照已记录，请到“我的订单”核对详细信息。' }
}

const saveConversation = () => {
  if (accountScope.value === 'auth-pending') return
  const saved: SavedConversation = { sessionId: sessionId.value, messages: messages.value, taskId: taskId.value || undefined }
  uni.setStorageSync(conversationKey(accountScope.value), JSON.stringify(saved))
}

const loadConversation = async (scope: string) => {
  accountScope.value = scope
  const raw = uni.getStorageSync(conversationKey(scope))
  let saved: SavedConversation | undefined
  try { saved = typeof raw === 'string' ? JSON.parse(raw) as SavedConversation : undefined } catch { saved = undefined }
  sessionId.value = saved?.sessionId || `${scope === 'guest' ? 'guest' : 'member'}_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`
  messages.value = saved?.messages?.length ? saved.messages : [{ role: 'assistant', content: '你好！我可以帮你了解商城政策、查询公开商品信息。登录后还可查询订单并准备售后申请。' }]
  taskId.value = saved?.taskId || ''
  task.value = null
  if (scope !== 'guest' && taskId.value) await refreshTask()
  saveConversation()
}

const syncMemberScope = async () => {
  const token = uni.getStorageSync('token')
  hasToken.value = Boolean(token)
  if (!token) {
    if (accountScope.value !== 'guest') await loadConversation('guest')
    return
  }
  let id = memberStore.memberInfo?.id
  if (!id) {
    try { const response = await getMemberInfoAPI(); memberStore.setMemberInfo(response.data); id = response.data?.id } catch { id = undefined }
  }
  if (!id) {
    accountScope.value = 'auth-pending'
    sessionId.value = `member_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`
    task.value = null
    taskId.value = ''
    messages.value = [{ role: 'assistant', content: '你好！我可以帮你了解商城政策和公开商品信息。' }]
    return
  }
  const scope = `member-${id}`
  if (scope !== accountScope.value && !switchingAccount) {
    switchingAccount = true
    try { await loadConversation(scope) } finally { switchingAccount = false }
  }
}

const loadStatus = async () => {
  try { const response = await agentStatusAPI(); agentStatus.value = response.data; serviceOnline.value = Boolean(response.data?.online && response.data?.ollamaOnline) }
  catch { serviceOnline.value = false }
}
const loadHelp = async () => { try { helpDocuments.value = (await knowledgeDocumentsAPI()).data?.documents || [] } catch { helpDocuments.value = [] } }

const toggleSourceFull = async (source: AgentSource) => {
  const key = sourceKey(source)
  if (expandedSources.value[key]) { delete expandedSources.value[key]; return }
  try {
    const response = await knowledgeSourceAPI(source.source, source.version)
    expandedSources.value[key] = response.data?.found && (!source.version || response.data.version === source.version) ? response.data.content : '该版本原文暂不可用，请联系人工客服核对。'
  } catch { expandedSources.value[key] = '原文读取失败，请稍后重试。' }
}

const ask = (question: string) => { inputMessage.value = question; sendMessage() }
const sendMessage = async () => {
  const content = inputMessage.value.trim()
  if (!content || loading.value) return
  await syncMemberScope()
  messages.value.push({ id: `u-${Date.now()}`, role: 'user', content })
  inputMessage.value = ''
  loading.value = true
  loadingTimer = setTimeout(() => { loadingText.value = '正在整理答案，请稍候…' }, 3500)
  saveConversation()
  try {
    const response = await customerChatAPI({ sessionId: sessionId.value, message: content })
    sessionId.value = response.data.sessionId || sessionId.value
    const data = response.data
    messages.value.push({ id: `a-${Date.now()}`, role: 'assistant', content: data.answer, meta: typeof data.responseTime === 'number' ? `客服回复 · ${(data.responseTime / 1000).toFixed(1)} 秒` : '客服回复', route: data.actualRoute || data.retrievalRoute, retrievalDecision: data.retrievalDecision, sources: data.sources || [], trace: data.trace, evidenceReport: data.evidenceReport })
  } catch {
    messages.value.push({ id: `a-${Date.now()}`, role: 'assistant', content: '暂时无法连接客服服务，请稍后再试。若刚才的问题涉及订单操作，请先核对订单状态。' })
  } finally {
    if (loadingTimer) clearTimeout(loadingTimer)
    loading.value = false
    saveConversation()
    setTimeout(() => { scrollTarget.value = `message-${messages.value.length - 1}` }, 30)
  }
}

const clearConversation = async () => {
  messages.value = [{ role: 'assistant', content: '新对话已开始。你可以继续咨询政策或商品。' }]
  sessionId.value = `${accountScope.value}_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`
  task.value = null
  taskId.value = ''
  saveConversation()
}

const loadOrders = async () => {
  if (!hasMemberIdentity.value || ordersLoading.value) return
  ordersLoading.value = true
  try {
    const response = await getOrderListAPI({ pageNum: 1, pageSize: 5, status: -1 })
    const page = response.data as unknown as { list?: OrderItem[]; records?: OrderItem[] }
    orders.value = page.list || page.records || []
    ordersLoaded.value = true
  } catch { orders.value = []; ordersLoaded.value = true }
  finally { ordersLoading.value = false }
}

const prepareAfterSale = async () => {
  if (!hasMemberIdentity.value || !canPrepare.value || workflowBusy.value) return
  workflowBusy.value = true
  try {
    const response = await prepareAfterSaleAPI({ sessionId: sessionId.value, orderSn: afterSale.value.orderSn.trim(), reason: afterSale.value.reason.trim(), description: afterSale.value.description.trim() })
    task.value = response.data
    taskId.value = response.data.taskId
    saveConversation()
  } catch { prepareOutcomeUnknown.value = true; uni.showToast({ icon: 'none', title: '请求结果待核对，请勿重复提交' }) }
  finally { workflowBusy.value = false }
}

const refreshTask = async () => {
  if (!taskId.value || accountScope.value === 'guest' || workflowBusy.value) return
  workflowBusy.value = true
  try {
    const response = await getAfterSaleAPI(taskId.value, sessionId.value)
    task.value = response.data
  } catch { task.value = { taskId: taskId.value, status: 'UNKNOWN', version: 0 }; uni.showToast({ icon: 'none', title: '无法核对申请状态，请稍后刷新或联系人工' }) }
  finally { workflowBusy.value = false }
}

const confirmTask = async (approved: boolean) => {
  if (!task.value || workflowBusy.value || task.value.status !== 'WAITING_CONFIRMATION') return
  workflowBusy.value = true
  try {
    const response = await confirmAfterSaleAPI(task.value.taskId, sessionId.value, task.value.version, approved)
    task.value = response.data
    saveConversation()
  } catch {
    // The server may have accepted a transition even if its response was lost; recover by reading state.
    try { task.value = (await getAfterSaleAPI(taskId.value, sessionId.value)).data }
    catch { task.value = { ...task.value, status: 'UNKNOWN' } }
    uni.showToast({ icon: 'none', title: task.value.status === 'UNKNOWN' ? '结果待核对，请刷新状态；不要重复提交' : '申请状态已刷新' })
  }
  finally { workflowBusy.value = false }
}

const chooseEvidenceImage = () => {
  if (visionBusy.value || !hasMemberIdentity.value) return
  uni.chooseImage({ count: 1, success: async (selection) => {
    const filePath = selection.tempFilePaths[0]
    if (!filePath) return
    visionBusy.value = true
    visionNote.value = ''
    try {
      const inspection: VisionInspection = await inspectAfterSaleImageAPI(filePath)
      afterSale.value.reason = inspection.draftReason || afterSale.value.reason
      visionNote.value = `图片识别仅供填写参考，请自行核实：${(inspection.observations || []).join('；')}`
    } catch { visionNote.value = '图片识别暂不可用，可手动填写申请原因。' }
    finally { visionBusy.value = false }
  } })
}

onLoad(() => { hasToken.value = Boolean(uni.getStorageSync('token')); loadStatus(); loadHelp() })
onShow(() => { void syncMemberScope(); if (!serviceOnline.value) void loadStatus() })
watch(() => memberStore.memberInfo?.id, () => { void syncMemberScope() })
</script>

<style lang="scss">
@media (min-width: 769px) {
  .agent-page .chat-card { min-height: 360px; height: max(360px, calc(100vh - 260px)); }
  .agent-page .side-panel { max-height: max(360px, calc(100vh - 260px)); overflow-y: auto; }
  .agent-page:has(.login-hint) .chat-card { height: max(360px, calc(100vh - 310px)); }
  .agent-page:has(.login-hint) .side-panel { max-height: max(360px, calc(100vh - 310px)); }
}
page { background: #f2f5fa; }
.agent-page { min-height: 100vh; padding: 28px 28px 44px; box-sizing: border-box; }
.agent-shell { max-width: 1440px; margin: 0 auto; }
.agent-hero { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 24px 32px; border-radius: 18px; color: #fff; background: linear-gradient(115deg, #172343, #314b9a 62%, #137777); box-shadow: 0 12px 32px #233e7d24; }
.eyebrow,.hero-title,.hero-subtitle { display: block; }.eyebrow { color: #a4e9ec; font-size: 12px; letter-spacing: 1.5px; }.hero-title { margin-top: 5px; font-size: 27px; font-weight: 700; }.hero-subtitle { margin-top: 5px; color: #dce8ff; font-size: 14px; }.service-status { display: flex; align-items: center; gap: 8px; color: #e5f7ed; font-size: 13px; white-space: nowrap; }.status-dot { width: 8px; height: 8px; border-radius: 50%; background: #4fe09b; }.status-dot.offline { background: #e27878; }
.login-hint { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 12px; padding: 9px 14px; border: 1px solid #f0d7a1; border-radius: 10px; color: #795a29; background: #fff8e8; font-size: 13px; }.login-button,.history-button { margin: 0; padding: 0 13px; height: 32px; border-radius: 8px; color: #4860c6; background: #eef2ff; font-size: 12px; }
.workspace { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 16px; margin-top: 16px; align-items: start; }.chat-card,.side-panel { border: 1px solid #e2e8f1; border-radius: 15px; background: #fff; box-shadow: 0 6px 24px #1e37640d; }.chat-card { display: flex; min-height: min(760px, calc(100vh - 154px)); height: calc(100vh - 154px); flex-direction: column; overflow: hidden; }.chat-heading { display: flex; justify-content: space-between; align-items: center; padding: 14px 20px; border-bottom: 1px solid #edf0f5; }.heading-title,.heading-subtitle { display: block; }.heading-title { color: #293652; font-size: 16px; font-weight: 700; }.heading-subtitle { margin-top: 3px; color: #929db0; font-size: 12px; }.history-button { color: #68748a; background: #f4f6fa; }
.message-list { flex: 1; min-height: 0; padding: 22px 24px 10px; box-sizing: border-box; background: linear-gradient(180deg,#fcfdff,#f7f9fc); }.message-row { display: flex; gap: 10px; margin-bottom: 18px; }.message-row.user { flex-direction: row-reverse; }.message-avatar { display: grid; place-items: center; width: 32px; height: 32px; flex: 0 0 auto; border-radius: 10px; color: #fff; background: linear-gradient(135deg,#536bd8,#7359d2); font-size: 10px; }.user .message-avatar { background: linear-gradient(135deg,#1a9b8c,#2476af); }.message-content { display: flex; max-width: 78%; flex-direction: column; align-items: flex-start; }.user .message-content { align-items: flex-end; }.message-bubble { display: block; padding: 11px 14px; border: 1px solid #e2e7f0; border-radius: 4px 13px 13px; color: #39455e; background: #fff; font-size: 14px; line-height: 1.7; white-space: pre-wrap; }.user .message-bubble { border: 0; border-radius: 13px 4px 13px 13px; color: #fff; background: #4b62cf; }.message-meta { margin: 5px 2px 0; color: #9aa3b2; font-size: 11px; }.loading-text { color: #75829a; }.source-list { width: 100%; margin-top: 7px; }.source-item { margin-top: 6px; padding: 10px 12px; border: 1px solid #e0e7f2; border-radius: 9px; background: #f8faff; }.source-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }.source-name { color: #4053a6; font-size: 12px; font-weight: 600; overflow-wrap: anywhere; }.source-action { color: #3d60d3; font-size: 11px; text-decoration: underline; white-space: nowrap; }.source-meta,.source-excerpt,.source-full { display: block; }.source-meta { margin-top: 3px; color: #929db0; font-size: 10px; }.source-excerpt { margin-top: 4px; color: #748099; font-size: 12px; line-height: 1.55; }.source-full { margin-top: 7px; padding-top: 7px; border-top: 1px dashed #dfe6f1; color: #53617c; font-size: 12px; line-height: 1.7; white-space: pre-wrap; }.technical-details { margin-top: 8px; color: #77839a; font-size: 11px; }.technical-details summary { cursor: pointer; color: #66759a; }.debug-line { display: block; margin-top: 3px; }.warn { color: #a05a2c; }
.quick-row { display: flex; flex-wrap: wrap; gap: 7px; padding: 6px 18px 10px; }.quick-chip { margin: 0; padding: 0 10px; height: 28px; border: 1px solid #e3e8f2; border-radius: 99px; color: #66738c; background: #fff; font-size: 11px; line-height: 26px; }.composer { display: flex; align-items: flex-end; gap: 10px; padding: 12px 16px; border-top: 1px solid #edf0f5; background: #fff; }.composer-input { flex: 1; min-height: 43px; max-height: 110px; padding: 10px 12px; box-sizing: border-box; border: 1px solid #dfe5ef; border-radius: 9px; color: #34405a; font-size: 14px; }.send-button { width: 76px; height: 43px; margin: 0; padding: 0; border-radius: 9px; color: #fff; background: #5268d5; font-size: 13px; }.send-button[disabled] { opacity: .5; }
.side-panel { overflow: hidden; }.side-section { padding: 14px 15px; border-bottom: 1px solid #edf0f5; }.side-section:last-child { border-bottom: 0; }.side-section summary { color: #2d3a55; font-size: 13px; font-weight: 700; cursor: pointer; }.section-copy,.empty-note { display: block; margin-top: 9px; color: #8792a6; font-size: 11px; line-height: 1.6; }.help-row,.order-row { display: flex; justify-content: space-between; gap: 7px; margin-top: 9px; padding-top: 8px; border-top: 1px solid #f0f2f6; }.help-title,.order-number { color: #4d5d80; font-size: 11px; overflow-wrap: anywhere; }.help-type,.order-status { color: #9aa3b2; font-size: 10px; text-align: right; }.panel-action,.confirm-button { width: 100%; height: 34px; margin: 10px 0 0; padding: 0 8px; border-radius: 8px; color: #fff; background: #5268d5; font-size: 12px; }.panel-action[disabled],.confirm-button[disabled] { opacity: .5; }.side-login { display: flex; flex-direction: column; gap: 8px; margin-top: 9px; color: #8792a6; font-size: 11px; }.link { color: #5268d5; cursor: pointer; }.field { width: 100%; height: 35px; margin-top: 8px; padding: 0 9px; box-sizing: border-box; border: 1px solid #e0e6ef; border-radius: 7px; color: #3f4d68; font-size: 11px; }.description { height: 62px; padding-top: 8px; }.image-action,.refresh-button,.reject-button { height: 31px; margin-top: 7px; padding: 0 8px; border: 1px solid #e1e6ef; border-radius: 7px; color: #65718a; background: #fff; font-size: 10px; }.image-action { width: 100%; }.image-action[disabled],.refresh-button[disabled],.reject-button[disabled] { opacity: .5; }.task-card { margin-top: 12px; padding: 10px; border: 1px solid #dce5f5; border-radius: 9px; background: #f8faff; }.task-status { display: block; color: #4053a6; font-size: 12px; font-weight: 700; }.task-detail { display: block; margin-top: 6px; color: #65718a; font-size: 11px; line-height: 1.6; white-space: pre-wrap; }.task-actions { display: flex; gap: 7px; }.reject-button,.confirm-button { flex: 1; width: auto; }.confirm-button { height: 31px; margin-top: 7px; font-size: 10px; }.warn-note { display: block; margin-top: 8px; color: #a05a2c; font-size: 10px; line-height: 1.5; }.refresh-button { width: 100%; }
@media (max-width: 768px) { .agent-page { padding: 0 0 20px; }.agent-hero { padding: 19px 17px; border-radius: 0 0 15px 15px; }.hero-title { font-size: 24px; }.hero-subtitle { max-width: 75vw; font-size: 12px; line-height: 1.5; }.service-status { font-size: 11px; }.login-hint { margin: 10px 10px 0; font-size: 11px; }.workspace { display: flex; flex-direction: column; gap: 10px; margin-top: 10px; }.chat-card { width: 100%; height: min(72vh, 700px); min-height: 520px; border-right: 0; border-left: 0; border-radius: 0; }.chat-heading { padding: 11px 14px; }.message-list { padding: 15px 12px 8px; }.message-content { max-width: 86%; }.message-bubble { font-size: 13px; }.quick-row { padding-right: 10px; padding-left: 10px; }.composer { padding: 9px 10px; }.side-panel { width: calc(100% - 20px); margin: 0 10px; box-sizing: border-box; }.side-section { padding: 13px; } }
</style>
