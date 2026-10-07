import http from '@/utils/http'
export interface ChatModelConfig { provider: 'ollama' | 'openai-compatible'; model: string; baseUrl?: string; apiKey?: string }
export interface ChatModelCatalog { defaultModel: string; models: Array<{ name: string; size: number; parameterSize: string; canChat: boolean }> }
export const getChatModelsAPI = (refresh = false) => http<ChatModelCatalog>({ url: '/agent/customer/api/v1/models', method: 'get', params: { refresh }, headers: { Authorization: '' } })
export const testChatModelAPI = (data: ChatModelConfig) => http<{ selectedModel: string; usedModels: string[]; reply: string }>({ url: '/agent/customer/api/v1/models/test', method: 'post', data, timeout: 60000, headers: { Authorization: '' } })

export interface ChatRequest {
  modelConfig?: ChatModelConfig
  message: string
  sessionId?: string
}

export interface ChatResponse {
  selectedModel?: string
  modelProvider?: string
  usedModels?: string[]
  generationUsed?: boolean
  resolutionStatus?: 'HANDOFF_RECOMMENDED' | 'RESPONSE_PROVIDED'
  handoffStatus?: 'NOT_CONNECTED'
  sessionId: string
  message: string
  answer: string
  intent: string
  responseTime: number
  sources: SourceReference[]
  collaboration?: CollaborationPlan
  retrievalDecision?: string
  evidenceScore?: number
  retrievalRoute?: string
  reranker?: string
  correctionCount?: number
  knowledgeVersion?: string
  trace?: AgentTraceSummary
  evidenceReport?: EvidenceReport
  graph?: PolicyGraphResult
}

export interface AgentTraceSummary {
  traceId: string
  stages: Array<{ name: string; durationMs: number; outcome: string }>
  modelCalls: number
  inputTokens: number
  outputTokens: number
}

export interface EvidenceReport {
  method: string
  checkedClaims: number
  unsupportedNumericClaims: number
  claims: Array<{ text: string; evidenceIds: string[]; support: string }>
  conflicts?: string[]
}

export interface PolicyGraphResult {
  status: string
  paths: Array<{
    from: string
    to: string
    sharedTopic: string
    fromVersion: string
    toVersion: string
  }>
  retrievalHints: string[]
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

export interface AfterSaleWorkflowState {
  taskId: string
  ownerId?: string
  sessionId?: string
  orderSn: string
  reason: string
  description?: string
  orderSummary: string
  policyAnswer: string
  draft: string
  status: 'WAITING_CONFIRMATION' | 'EXECUTING' | 'COMPLETED' | 'REJECTED' | 'UNKNOWN' | string
  version: number
  result?: string
  createdAt?: string
  updatedAt?: string
}

export interface MemberLoginResult {
  token: string
  tokenHead: string
}

export interface VisionInspectionResult {
  model: string
  observations: string[]
  draftReason: string
  requiresReview: boolean
  elapsedMs: number
}

export interface SourceReference {
  contentKind?: 'selected-excerpt' | 'source-preview'
  id: string
  source: string
  type: string
  content: string
  score: number
  retrievalSource: string
  version?: string
  contentHash?: string
  effectiveAt?: string
  scope?: string
  evidenceVerified?: boolean
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
  method?: string
  apiPath?: string
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
  /** 开跑前的环境可达性结论：用于区分"环境挂了"与"代码坏了" */
  environment?: {
    module?: string
    target?: string
    reachable: boolean
    skipped: boolean
    statusCode?: number | null
    latencyMs?: number
    detail?: string
  }
  startTime: string
  endTime: string
}

export interface TestCapabilities {
  online: boolean
  modules: string[]
  aiEnabled: boolean
  model: string
  /** 入口是否要求凭证（fail-closed） */
  authRequired?: boolean
  /** MCP 端点是否启用 */
  mcpEnabled?: boolean
  pipeline: string[]
}

export interface TestReportComparison {
  baselineReportId: string
  currentReportId: string
  comparable: boolean
  warnings: string[]
  counts: Record<string, number>
  entries: Array<{
    category: string
    caseKey: string
    testCaseName: string
    baselinePassed?: boolean | null
    currentPassed?: boolean | null
    baselineStatusCode?: number | null
    currentStatusCode?: number | null
    baselineError?: string | null
    currentError?: string | null
  }>
}

export function compareTestReportsAPI(baselineId: string, currentId: string) {
  return http<TestReportComparison>({
    url: '/agent/test/api/v1/test/reports/compare',
    method: 'get',
    params: { baselineId, currentId },
  })
}

// 客服 Agent
export function sendChatMessageAPI(data: ChatRequest, authorization = '') {
  return http<ChatResponse>({
    url: '/agent/customer/api/v1/chat',
    method: 'post',
    data,
    headers: { Authorization: authorization },
    timeout: 120000,
  })
}

export function loginMemberAPI(username: string, password: string) {
  return http<MemberLoginResult>({
    url: '/api/sso/login',
    method: 'post',
    data: new URLSearchParams({ username, password }),
    headers: { Authorization: '', 'Content-Type': 'application/x-www-form-urlencoded' },
    timeout: 15000,
  })
}

export function getRagStatusAPI() {
  return http<RagStatus>({
    url: '/agent/customer/api/v1/knowledge/status',
    method: 'get',
    headers: { Authorization: '' },
  })
}

export function getRagCacheStatsAPI() {
  return http<Record<string, number>>({
    url: '/agent/customer/api/v1/knowledge/cache/stats',
    method: 'get',
    headers: { Authorization: '' },
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

export function getKnowledgeSourceAPI(source: string, version?: string) {
  return http<{ found: boolean; source: string; type: string; content: string }>({
    url: '/agent/customer/api/v1/knowledge/source',
    method: 'get',
    params: { source, version },
    headers: { Authorization: '' },
    timeout: 10000,
  })
}

export function executeTaskAPI(data: { sessionId?: string; message: string }, authorization = '') {
  return http<TaskResponse>({
    url: '/agent/customer/api/v1/task/execute',
    method: 'post',
    data,
    headers: { Authorization: authorization },
    timeout: 120000,
  })
}

export function prepareAfterSaleAPI(data: {
  sessionId: string
  orderSn: string
  reason: string
  description: string
}, authorization: string) {
  return http<AfterSaleWorkflowState>({
    url: '/agent/customer/api/v1/workflows/after-sale',
    method: 'post',
    data,
    headers: { Authorization: authorization },
    timeout: 120000,
  })
}

export function confirmAfterSaleAPI(taskId: string, expectedVersion: number, approved: boolean,
                                    authorization: string, sessionId: string) {
  return http<AfterSaleWorkflowState>({
    url: `/agent/customer/api/v1/workflows/after-sale/${encodeURIComponent(taskId)}/confirm`,
    method: 'post',
    data: { expectedVersion, approved },
    headers: { Authorization: authorization, 'X-Session-Id': sessionId },
    timeout: 60000,
  })
}

export function getAfterSaleAPI(taskId: string, authorization: string, sessionId: string) {
  return http<AfterSaleWorkflowState>({
    url: `/agent/customer/api/v1/workflows/after-sale/${encodeURIComponent(taskId)}`,
    method: 'get',
    headers: { Authorization: authorization, 'X-Session-Id': sessionId },
    timeout: 60000,
  })
}

export function inspectCustomerPhotoAPI(file: File, authorization = '') {
  const data = new FormData()
  data.append('file', file)
  return http<VisionInspectionResult>({
    url: '/agent/customer/api/v1/vision/inspect',
    method: 'post',
    data,
    headers: { Authorization: authorization },
    timeout: 120000,
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

// 运维控制面：指标上报 / 巡检 / 门控审批 / 模拟执行

export function reportMetricAPI(data: { metric_name: string; metric_value: number; target_service: string }) {
  return http<{ accepted: boolean; incidentTriggered: boolean; incident: IncidentState | null }>({
    url: '/agent/ops/api/v1/incidents/metrics',
    method: 'post',
    data,
    timeout: 60000,
  })
}

export function watchOnceAPI() {
  return http<{ targets: number; ticks: Array<Record<string, unknown>> }>({
    url: '/agent/ops/api/v1/incidents/metrics/watch-once',
    method: 'post',
    timeout: 60000,
  })
}

export interface GateRecord {
  id: string
  alertId: string
  playbook: string
  riskScore: number
  approver: string
  status: string
  reason: string
  createdAt: string
  decidedAt?: string | null
  decidedBy?: string | null
  executionSummary?: string | null
}

export function getGateDecisionsAPI(status?: 'pending_approval') {
  return http<GateRecord[]>({
    url: `/agent/ops/api/v1/incidents/gate-decisions${status ? `?status=${status}` : ''}`,
    method: 'get',
  })
}

export function approveGateAPI(gateId: string, approver: string) {
  return http<{ gate: GateRecord; execution: Record<string, unknown> }>({
    url: `/agent/ops/api/v1/incidents/gate-decisions/${gateId}/approve?approver=${encodeURIComponent(approver)}`,
    method: 'post',
    timeout: 60000,
  })
}

export function rejectGateAPI(gateId: string, approver: string, reason: string) {
  return http<GateRecord>({
    url: `/agent/ops/api/v1/incidents/gate-decisions/${gateId}/reject`
      + `?approver=${encodeURIComponent(approver)}&reason=${encodeURIComponent(reason)}`,
    method: 'post',
  })
}

export function getPlaybooksAPI() {
  return http<{ executionMode: string; note: string; items: Array<Record<string, unknown>> }>({
    url: '/agent/ops/api/v1/incidents/playbooks',
    method: 'get',
  })
}

export function getExecutionsAPI() {
  return http<Array<Record<string, unknown>>>({
    url: '/agent/ops/api/v1/incidents/executions',
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
