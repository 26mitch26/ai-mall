import { http } from '@/utils/http'

export interface AgentSource {
  contentKind?: 'selected-excerpt' | 'source-preview'
  id: string
  source: string
  type: string
  content: string
  score: number | null
  retrievalSource: string
  version?: string
  contentHash?: string
  effectiveAt?: string
  scope?: string
  evidenceVerified?: boolean
}

export interface AgentTrace {
  traceId?: string
  stages?: Array<{ name: string; durationMs: number; outcome?: string }>
  modelCalls?: number
  inputTokens?: number
  outputTokens?: number
}

export interface EvidenceClaim { text: string; evidenceIds: string[]; support: string }
export interface EvidenceReport {
  method?: string
  checkedClaims?: number
  unsupportedNumericClaims?: number
  claims?: EvidenceClaim[]
  conflicts?: string[]
}

export interface AgentCollaborationNode {
  id: string
  name: string
  role: string
  description: string
  status: string
  active: boolean
}

export interface CollaborationPlan {
  routeSummary: string
  activeAgent: string
  agents: AgentCollaborationNode[]
}

export interface CustomerChatResponse {
  resolutionStatus?: 'HANDOFF_RECOMMENDED' | 'RESPONSE_PROVIDED'
  handoffStatus?: 'NOT_CONNECTED'
  sessionId: string
  answer: string
  intent: string
  responseTime: number
  sources?: AgentSource[]
  collaboration?: CollaborationPlan
  /** 检索判定结论：依据充分 / 依据不足转人工 / 实时业务数据 */
  retrievalDecision?: string
  /** 证据强度（top-1 语义相似度为主信号，0~1） */
  evidenceScore?: number | null
  actualRoute?: string
  retrievalRoute?: string
  reranker?: string
  correctionCount?: number
  knowledgeVersion?: string
  trace?: AgentTrace
  evidenceReport?: EvidenceReport
}

export interface AgentStatus {
  online: boolean
  ollamaOnline: boolean
  chatModel: string
  embeddingModel: string
  vectorStore: string
  vectorIndex: string
  retrieval: string
  pipeline?: string[]
  /** 知识库规模：文档数与分块数（BM25 索引实测） */
  knowledgeBase?: { documents: number; chunks: number }
}

export interface KnowledgeSourceDetail {
  found: boolean
  source: string
  type: string
  content: string
  version?: string
  contentHash?: string
  effectiveAt?: string
  scope?: string
}

export interface CustomerChatParam {
  sessionId: string
  message: string
}

export const customerChatAPI = (data: CustomerChatParam) => {
  return http<CustomerChatResponse>({
    method: 'POST',
    url: '/agent/customer/api/v1/chat',
    data,
  })
}

/** RAG 运行状态，用于会员端展示本地模型与检索链路是否在线 */
export const agentStatusAPI = () => {
  return http<AgentStatus>({
    method: 'GET',
    url: '/agent/customer/api/v1/knowledge/status',
  })
}

/** 按来源标识读取知识库原文全文，支撑回答里来源卡片的"查看原文" */
export const knowledgeSourceAPI = (source: string, version?: string) => {
  return http<KnowledgeSourceDetail>({
    method: 'GET',
    url: '/agent/customer/api/v1/knowledge/source',
    data: { source, version },
  })
}

export interface AfterSaleWorkflowState {
  taskId: string
  workflowType?: string
  orderSn?: string
  reason?: string
  description?: string
  orderSummary?: string
  policyAnswer?: string
  policyEvidenceWeak?: boolean
  draft?: string
  status: string
  version: number
  result?: string
  createdAt?: string
  updatedAt?: string
}

export const prepareAfterSaleAPI = (data: { sessionId: string; orderSn: string; reason: string; description: string }) =>
  http<AfterSaleWorkflowState>({ method: 'POST', url: '/agent/customer/api/v1/workflows/after-sale', data })

export const getAfterSaleAPI = (taskId: string, sessionId: string) =>
  http<AfterSaleWorkflowState>({ method: 'GET', url: `/agent/customer/api/v1/workflows/after-sale/${encodeURIComponent(taskId)}`, header: { 'X-Session-Id': sessionId } })

export const confirmAfterSaleAPI = (taskId: string, sessionId: string, expectedVersion: number, approved: boolean) =>
  http<AfterSaleWorkflowState>({ method: 'POST', url: `/agent/customer/api/v1/workflows/after-sale/${encodeURIComponent(taskId)}/confirm`, header: { 'X-Session-Id': sessionId }, data: { expectedVersion, approved } })

export interface VisionInspection {
  model: string
  observations: string[]
  draftReason: string
  requiresReview: boolean
  elapsedMs: number
}

export const inspectAfterSaleImageAPI = (filePath: string) => new Promise<VisionInspection>((resolve, reject) => {
  uni.uploadFile({
    url: '/agent/customer/api/v1/vision/inspect', filePath, name: 'file',
    success: (result) => {
      if (result.statusCode < 200 || result.statusCode >= 300) { reject(result); return }
      try { resolve(JSON.parse(result.data as string) as VisionInspection) } catch (error) { reject(error) }
    },
    fail: reject,
  })
})

export interface KnowledgeDocument {
  /** 来源标识（如 docs/knowledge/refund-policy.md），来源溯源唯一键 */
  source: string
  /** 知识版本；重复来源时优先展示有版本标识的有效文档 */
  version?: string
  /** 文档类型：policy / faq 等 */
  type: string
  /** 展示名（入库正文首个标题，缺省为文件名） */
  title: string
}

export interface KnowledgeDocumentList {
  total: number
  documents: KnowledgeDocument[]
}

const dedupeKnowledgeDocuments = (documents: KnowledgeDocument[]) => {
  const chosen = new Map<string, KnowledgeDocument>()

  for (const document of documents) {
    const key = document.source
    const current = chosen.get(key)
    const hasVersion = Boolean(document.version?.trim())
    const currentHasVersion = Boolean(current?.version?.trim())

    if (!current || (!currentHasVersion && hasVersion)) {
      chosen.set(key, document)
    }
  }

  return Array.from(chosen.values())
}

/** 知识库文档清单，支撑会员端"帮助中心"列出全部政策文档 */
export const knowledgeDocumentsAPI = () => {
  return http<KnowledgeDocumentList>({
    method: 'GET',
    url: '/agent/customer/api/v1/knowledge/documents',
  }).then((response) => {
    const documents = dedupeKnowledgeDocuments(response.data?.documents || [])
    return {
      ...response,
      data: {
        ...response.data,
        total: documents.length,
        documents,
      },
    }
  })
}
