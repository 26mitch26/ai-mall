import { http } from '@/utils/http'

export interface AgentSource {
  id: string
  source: string
  type: string
  content: string
  score: number
  retrievalSource: string
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
  sessionId: string
  answer: string
  intent: string
  responseTime: number
  sources?: AgentSource[]
  collaboration?: CollaborationPlan
  /** 检索判定结论：依据充分 / 依据不足转人工 / 实时业务数据 */
  retrievalDecision?: string
  /** 证据强度（top-1 语义相似度为主信号，0~1） */
  evidenceScore?: number
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
}

export interface CustomerChatParam {
  sessionId: string
  message: string
  /**
   * 当前登录会员 ID。订单查询、创建售后等敏感工具需要它做身份绑定与数据隔离，
   * 未登录时可以不传，公开的商品与政策问答不受影响。
   */
  userId?: string
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
export const knowledgeSourceAPI = (source: string) => {
  return http<KnowledgeSourceDetail>({
    method: 'GET',
    url: '/agent/customer/api/v1/knowledge/source',
    data: { source },
  })
}

export interface KnowledgeDocument {
  /** 来源标识（如 docs/knowledge/refund-policy.md），来源溯源唯一键 */
  source: string
  /** 文档类型：policy / faq 等 */
  type: string
  /** 展示名（入库正文首个标题，缺省为文件名） */
  title: string
}

export interface KnowledgeDocumentList {
  total: number
  documents: KnowledgeDocument[]
}

/** 知识库文档清单，支撑会员端"帮助中心"列出全部政策文档 */
export const knowledgeDocumentsAPI = () => {
  return http<KnowledgeDocumentList>({
    method: 'GET',
    url: '/agent/customer/api/v1/knowledge/documents',
  })
}