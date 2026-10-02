import http from '@/utils/http'

export interface ChatRequest {
  message: string
  sessionId?: string
  userId?: string
}

export interface ChatResponse {
  sessionId: string
  message: string
  answer: string
  intent: string
  responseTime: number
  sources: SourceReference[]
  collaboration?: CollaborationPlan
}

export interface AgentCollaboration {
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
  agents: AgentCollaboration[]
}

export interface TaskResponse {
  sessionId: string
  task: string
  taskName: string
  dpmAction: string
  nextPrompt: string
  completed: boolean
  dstState: {
    intent?: string
    slots?: Record<string, unknown>
    filledSlots?: string[]
    missingSlots?: string[]
    turn?: number
  }
  apiCall?: string
  result?: string
}

export interface SourceReference {
  id: string
  source: string
  type: string
  content: string
  score: number
  retrievalSource: string
}

export interface RagStatus {
  online: boolean
  ollamaOnline: boolean
  chatModel: string
  embeddingModel: string
  vectorStore: string
  vectorIndex: string
  retrieval: string
  pipeline: string[]
  cache?: Record<string, number>
}

export interface AlertEvent {
  id: string
  metricName: string
  metricValue: number
  targetService: string
  severity: string
  timestamp: string
  status: string
}

export interface IncidentState {
  id: string
  alert?: AlertEvent
  rcaResult?: {
    rootCause: string
    confidence: number
    impactChain: string[]
    suggestedActions: string[]
    analysisSummary?: string
  }
  healAction?: {
    level: string
    action: string
    playbook: string
    blastRadius: number
    status: string
    dryRunPassed: boolean
  }
  changeDecision?: {
    riskScore: number
    approver: string
    status: string
    reason: string
  }
  status: string
  startTime: string
  endTime?: string
}

export interface OpsCapabilities {
  online: boolean
  mode: string
  llmModel: string
  pipeline: string[]
  algorithms: string[]
}

export interface AssertionDetail {
  assertionName?: string
  passed: boolean
  expected?: string
  actual?: string
  message?: string
}

export interface TestResult {
  testCaseId: string
  testCaseName: string
  passed: boolean
  actualStatusCode: number
  actualResponse?: string
  executionTime: number
  errorMessage?: string
  timestamp?: string
  assertionDetails?: AssertionDetail[]
}

export interface TestReport {
  id: string
  moduleName: string
  totalTests: number
  passedTests: number
  failedTests: number
  passRate: number
  averageResponseTime: number
  totalExecutionTime: number
  assertionsTotal: number
  assertionsPassed: number
  assertionsFailed: number
  results: TestResult[]
  knownDefects?: Array<{ id?: string; summary?: string; occurrenceCount?: number }>
  startTime: string
  endTime: string
}

export interface TestCapabilities {
  online: boolean
  modules: string[]
  aiEnabled: boolean
  model: string
  pipeline: string[]
}

// 客服 Agent
export function sendChatMessageAPI(data: ChatRequest) {
  return http<ChatResponse>({
    url: '/agent/customer/api/v1/chat',
    method: 'post',
    data,
    timeout: 60000,
  })
}

export function getRagStatusAPI() {
  return http<RagStatus>({
    url: '/agent/customer/api/v1/knowledge/status',
    method: 'get',
  })
}

export function getRagCacheStatsAPI() {
  return http<Record<string, number>>({
    url: '/agent/customer/api/v1/knowledge/cache/stats',
    method: 'get',
  })
}

export function ingestKnowledgeAPI(data: {
  content: string
  source?: string
  type?: string
  chunkStrategy?: string
}) {
  return http<{ indexedChunks: number; docId: string }>({
    url: '/agent/customer/api/v1/knowledge/ingest',
    method: 'post',
    data,
    timeout: 120000,
  })
}

export function getKnowledgeSourceAPI(source: string) {
  return http<{ found: boolean; source: string; type: string; content: string }>({
    url: '/agent/customer/api/v1/knowledge/source',
    method: 'get',
    params: { source },
    timeout: 10000,
  })
}

export function executeTaskAPI(data: { sessionId?: string; message: string; userId?: string; userToken?: string }) {
  return http<TaskResponse>({
    url: '/agent/customer/api/v1/task/execute',
    method: 'post',
    data,
    timeout: 60000,
  })
}

// 运维 Agent
export function triggerIncidentAPI(data: {
  metric_name: string
  metric_value: number
  target_service: string
  demo_mode?: boolean
}) {
  return http<IncidentState | null>({
    url: '/agent/ops/api/v1/incidents/trigger',
    method: 'post',
    data,
    timeout: 60000,
  })
}

export function getIncidentAPI(id: string) {
  return http<IncidentState>({
    url: `/agent/ops/api/v1/incidents/${id}`,
    method: 'get',
  })
}

export function getAllIncidentsAPI() {
  return http<Record<string, IncidentState>>({
    url: '/agent/ops/api/v1/incidents',
    method: 'get',
  })
}

export function getOpsCapabilitiesAPI() {
  return http<OpsCapabilities>({
    url: '/agent/ops/api/v1/incidents/capabilities',
    method: 'get',
  })
}

// 自动化测试 Agent
export function generateTestReportAPI(module: string) {
  return http<TestReport>({
    url: '/agent/test/api/v1/test/generate',
    method: 'post',
    params: { module },
    timeout: 180000,
  })
}

export function getTestReportAPI(id: string) {
  return http<TestReport>({
    url: `/agent/test/api/v1/test/report/${id}`,
    method: 'get',
  })
}

export function getAllTestReportsAPI() {
  return http<TestReport[]>({
    url: '/agent/test/api/v1/test/reports',
    method: 'get',
  })
}

export function getTestCapabilitiesAPI() {
  return http<TestCapabilities>({
    url: '/agent/test/api/v1/test/capabilities',
    method: 'get',
  })
}
